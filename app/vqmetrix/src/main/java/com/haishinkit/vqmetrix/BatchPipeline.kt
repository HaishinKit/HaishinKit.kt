package com.haishinkit.vqmetrix

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.StatFs
import com.haishinkit.app.vqmetrix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BatchProgress(val stage: String, val current: Int, val total: Int, val detail: String = "")

class BatchPipeline(private val context: Context) {
    suspend fun create(
        config: BatchConfig,
        progress: (BatchProgress) -> Unit,
        preview: (Bitmap) -> Unit,
        screenReference: ScreenReference? = null,
    ): BatchArchive =
        withContext(Dispatchers.IO) {
            val spec = ReferenceSpec(config.width, config.height, config.fps, config.fps * config.seconds)
            val expectedBytes =
                spec.frameBytes.toLong() * spec.frames * 2 +
                    config.bitrates.sumOf { it.toLong() / 8 * config.seconds } + 32 * 1024 * 1024
            check(StatFs(context.filesDir.path).availableBytes > expectedBytes) { "Not enough free storage" }
            val id = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "runs/$id").apply { check(mkdirs()) }
            val reference = File(directory, "reference.y4m")
            val started = Instant.now().toString()

            fun onFrame(
                index: Int,
                actual: ReferenceSpec,
                frame: ByteArray,
            ) {
                progress(BatchProgress("reference", index, actual.frames))
                if (index == 1 || index % maxOf(1, actual.fps / 5) == 0) preview(YuvImages.preview(frame, actual))
            }
            try {
                val captured =
                    if (screenReference != null) {
                        screenReference.record(reference, config, ::onFrame)
                    } else if (config.camera) {
                        CameraReference(context).record(reference, config, ::onFrame)
                    } else {
                        Y4mWriter(reference, spec).use { writer ->
                            for (index in 0 until spec.frames) {
                                currentCoroutineContext().ensureActive()
                                val frame = TestPattern.frame(spec, index)
                                writer.append(frame)
                                onFrame(index + 1, spec, frame)
                            }
                        }
                        CapturedReference(spec, "test-pattern-v1", emptyList(), "limited", "bt709")
                    }
                val variants = ArrayList<EncodedVariant>()
                for (bitrate in config.bitrates) {
                    currentCoroutineContext().ensureActive()
                    val file = File(directory, "${config.codec}-$bitrate.mp4")
                    variants.add(
                        VariantEncoder(context).encode(reference, captured, file, bitrate, config.codec) {
                            progress(BatchProgress("encoding", it, captured.spec.frames, "${config.codec} · ${bitrate / 1000} kbps"))
                        },
                    )
                }
                progress(BatchProgress("packaging", 0, 1))
                val frameMap =
                    JSONObject().apply {
                        put("referencePtsUs", JSONArray((0 until captured.spec.frames).map { VariantEncoder.pts(it, captured.spec.fps) }))
                        put("captureTimestampsNs", JSONArray(captured.captureTimestampsNs))
                        put(
                            "variants",
                            JSONObject().apply {
                                variants.forEach { put(it.file.name, JSONArray(it.outputPtsUs)) }
                            },
                        )
                    }
                val mapFile = File(directory, "frame-map.json").apply { writeText(frameMap.toString(2)) }
                val files = listOf(reference, mapFile) + variants.map { it.file }
                val manifest =
                    JSONObject().apply {
                        put("schema", "vqmetrix.capture.v1")
                        put("schemaVersion", 1)
                        put("runId", id)
                        put("createdAt", started)
                        put(
                            "device",
                            JSONObject().apply {
                                put("manufacturer", Build.MANUFACTURER)
                                put("model", Build.MODEL)
                                put("androidApi", Build.VERSION.SDK_INT)
                                put("appVersion", BuildConfig.VQMETRIX_VERSION)
                            },
                        )
                        put(
                            "reference",
                            JSONObject().apply {
                                put("file", reference.name)
                                put("source", captured.source)
                                put("width", captured.spec.width)
                                put("height", captured.spec.height)
                                put("fpsNumerator", captured.spec.fps)
                                put("fpsDenominator", 1)
                                put("frameCount", captured.spec.frames)
                                put("pixelFormat", "yuv420p")
                                put("bitDepth", 8)
                                put("colorRange", captured.range)
                                put("colorMatrix", captured.matrix)
                                put("sensorOrientationDegrees", captured.sensorOrientation)
                                put("frameMap", mapFile.name)
                            },
                        )
                        put(
                            "variants",
                            JSONArray().apply {
                                variants.forEach { variant ->
                                    put(
                                        JSONObject().apply {
                                            put("file", variant.file.name)
                                            put("codec", variant.codec)
                                            put("targetBitrate", variant.bitrate)
                                            put("actualBitrate", variant.encodedBytes * 8.0 * captured.spec.fps / captured.spec.frames)
                                            put("codecName", variant.codecName)
                                            put("performance", variant.performance)
                                            put("hardwareAccelerated", variant.hardwareAccelerated)
                                            put("frameCount", variant.outputPtsUs.size)
                                            put("width", captured.spec.width)
                                            put("height", captured.spec.height)
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "files",
                            JSONArray().apply {
                                files.forEach { file ->
                                    put(
                                        JSONObject().apply {
                                            put("path", file.name)
                                            put("size", file.length())
                                            put("sha256", sha256(file))
                                        },
                                    )
                                }
                            },
                        )
                    }
                val manifestFile = File(directory, "manifest.json").apply { writeText(manifest.toString(2)) }
                val temporary = File(directory, "batch.zip.part")
                val archive = File(directory, "batch.zip")
                ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                    zip.setLevel(1)
                    for (file in listOf(manifestFile) + files) {
                        currentCoroutineContext().ensureActive()
                        zip.putNextEntry(ZipEntry(file.name).apply { time = 0 })
                        file.inputStream().buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
                check(temporary.renameTo(archive)) { "Could not finalize archive" }
                progress(BatchProgress("packaging", 1, 1))
                BatchArchive(id, directory, archive)
            } catch (failure: Throwable) {
                File(directory, "batch.zip.part").delete()
                File(directory, "failure.txt").writeText(failure.message ?: failure.javaClass.simpleName)
                throw failure
            }
        }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
