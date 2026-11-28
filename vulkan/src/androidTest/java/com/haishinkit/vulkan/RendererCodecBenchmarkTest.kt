package com.haishinkit.vulkan

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Size
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Screen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/** Opt-in, wall-clock throughput of the normal paced composition-to-encoder path. */
class RendererCodecBenchmarkTest {
    private data class Scenario(val width: Int, val height: Int, val fps: Int, val bitRate: Int)

    private data class Output(val arrivedNs: Long, val ptsUs: Long, val bytes: Int)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test(timeout = 1_200_000)
    fun comparePacedEncoderThroughput() {
        assumeTrue("Run explicitly with -e rendererBenchmark true", arguments.getString("rendererBenchmark") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 29 && VkPixelTransform.isSupported())
        val repetitions = arguments.getString("benchmarkRepetitions", "3").toInt().also { require(it in 1..5) }
        val seconds = arguments.getString("benchmarkSeconds", "10").toInt().also { require(it in 2..30) }
        val warmup = arguments.getString("benchmarkWarmupSeconds", "3").toInt().also { require(it in 1..10) }
        val scenarios = listOf(Scenario(1280, 720, 60, 8_000_000), Scenario(1920, 1080, 30, 16_000_000), Scenario(1920, 1080, 60, 16_000_000), Scenario(3840, 2160, 60, 40_000_000))
        require(repetitions * scenarios.size * 2 * (warmup + seconds + 2) < 1100) { "Requested run exceeds the test timeout" }
        val codec =
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first {
                it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }
        val results = JSONArray()
        val document =
            JSONObject().put("sourceRevision", arguments.getString("benchmarkRevision", "unknown"))
                .put("vulkanBuildType", BuildConfig.BUILD_TYPE).put("coreBuildType", com.haishinkit.BuildConfig.BUILD_TYPE)
                .put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
                .put("codec", codec.name).put("warmupSeconds", warmup).put("measurementSeconds", seconds)
                .put("repetitions", repetitions).put("results", results)
        repeat(repetitions) { repeat ->
            val ordered = scenarios.drop(repeat % scenarios.size) + scenarios.take(repeat % scenarios.size)
            ordered.forEachIndexed { index, scenario ->
                val backends = if ((repeat + index) % 2 == 0) listOf("OpenGL", "Vulkan") else listOf("Vulkan", "OpenGL")
                for (backend in backends) {
                    val result =
                        JSONObject().put("backend", backend).put("repeat", repeat + 1)
                            .put("width", scenario.width).put("height", scenario.height).put("targetFps", scenario.fps).put("bitRate", scenario.bitRate)
                    val capabilities = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    if (capabilities.videoCapabilities?.areSizeAndRateSupported(scenario.width, scenario.height, scenario.fps.toDouble()) != true ||
                        capabilities.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) != true
                    ) {
                        result.put("skipped", "The selected hardware encoder does not advertise this size/rate/CBR configuration")
                    } else {
                        measure(codec.name, backend, scenario, warmup, seconds, result)
                    }
                    results.put(result)
                    File(context.filesDir, "renderer-codec-benchmark.json").writeText(document.toString(2))
                    instrumentation.sendStatus(2, Bundle().apply { putString("benchmark_result", result.toString()) })
                    SystemClock.sleep(2000)
                }
            }
        }
        assertTrue("No supported trials ran", (0 until results.length()).any { results.getJSONObject(it).has("outputFps") })
        instrumentation.sendStatus(2, Bundle().apply { putString("benchmark_document", document.toString()) })
    }

    @Test
    fun exportLastResult() {
        assumeTrue(arguments.getString("rendererBenchmarkExport") == "true")
        val document = JSONObject(File(context.filesDir, "renderer-codec-benchmark.json").readText())
        instrumentation.sendStatus(2, Bundle().apply { putString("benchmark_document", document.toString()) })
    }

    private fun measure(
        codecName: String,
        backend: String,
        scenario: Scenario,
        warmup: Int,
        seconds: Int,
        result: JSONObject,
    ) {
        val codec = MediaCodec.createByCodecName(codecName)
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, scenario.width, scenario.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, scenario.bitRate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, scenario.fps)
                setInteger(MediaFormat.KEY_OPERATING_RATE, scenario.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        val outputs = ConcurrentLinkedQueue<Output>()
        val drainError = AtomicReference<Throwable?>()
        val renderError = AtomicReference<Throwable?>()
        val draining = AtomicBoolean(true)
        val validationAfter = AtomicLong(Long.MAX_VALUE)
        val validationFrame = AtomicReference<ByteArray?>()
        var picture: Pair<MediaFormat, ByteArray>? = null
        var started = false
        var drain: Thread? = null
        var screen: Screen? = null
        var transform: PixelTransform? = null
        var input: android.view.Surface? = null
        val bitmaps = mutableListOf<Bitmap>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = codec.createInputSurface()
            codec.start()
            started = true
            drain =
                Thread({
                    try {
                        val info = MediaCodec.BufferInfo()
                        while (draining.get()) {
                            val index = codec.dequeueOutputBuffer(info, 10_000)
                            if (index >= 0) {
                                if (info.size > 0 && info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                                    check(info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0) { "Partial access units are not supported by this benchmark" }
                                    outputs.add(Output(System.nanoTime(), info.presentationTimeUs, info.size))
                                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && System.nanoTime() >= validationAfter.get() && validationFrame.get() == null) {
                                        val bytes = ByteArray(info.size)
                                        codec.getOutputBuffer(index)!!.duplicate().apply {
                                            position(info.offset)
                                            limit(info.offset + info.size)
                                            get(bytes)
                                        }
                                        validationFrame.set(bytes)
                                    }
                                }
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    } catch (error: Throwable) {
                        drainError.set(error)
                    }
                }, "RendererBenchmarkDrain").apply { start() }
            val selected = if (backend == "Vulkan") VkScreen.create(context) else Screen.createOpenGL(context)
            screen = selected
            if (selected is VkScreen) selected.onError = { renderError.compareAndSet(null, it) }
            selected.frame = Rect(0, 0, scenario.width, scenario.height)
            selected.backgroundColor = Color.rgb(32, 32, 32)
            repeat(4) { index ->
                val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(
                    IntArray(128 * 128) { pixel ->
                        val x = pixel % 128
                        val y = pixel / 128
                        Color.rgb((x * 2 + index * 47) % 256, (y * 2 + index * 71) % 256, if ((x / 8 + y / 8) % 2 == 0) 32 else 224)
                    },
                    0,
                    128,
                    0,
                    0,
                    128,
                    128,
                )
                bitmaps.add(bitmap)
                selected.addChild(
                    ImageScreenObject().apply {
                        frame = Rect(index % 2 * scenario.width / 2, index / 2 * scenario.height / 2, (index % 2 + 1) * scenario.width / 2, (index / 2 + 1) * scenario.height / 2)
                        this.bitmap = bitmap
                    },
                )
            }
            val overlay = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xb0ffffff.toInt()) }
            bitmaps.add(overlay)
            val moving = ImageScreenObject().apply { bitmap = overlay }
            selected.addChild(moving)
            val ready = CountDownLatch(3)
            val animationStart = System.nanoTime()
            selected.registerCallback(
                object : Screen.Callback() {
                    override fun onEnterFrame() {
                        val size = scenario.height / 4
                        val left = (((System.nanoTime() - animationStart) / 1_000_000 % 4000) * (scenario.width - size) / 4000).toInt()
                        moving.frame = Rect(left, scenario.height / 3, left + size, scenario.height / 3 + size)
                        ready.countDown()
                    }
                },
            )
            check(ready.await(5, TimeUnit.SECONDS)) { "Composition did not initialize" }
            transform =
                (if (backend == "Vulkan") VkPixelTransform.create(context) else PixelTransform.createOpenGL(context)).apply {
                    imageExtent = Size(scenario.width, scenario.height)
                    videoGravity = VideoGravity.RESIZE
                    videoEffect = BicubicVideoEffect()
                    frameRate = scenario.fps
                    surface = input
                    this.screen = selected
                }
            SystemClock.sleep(warmup * 1000L)
            check(outputs.isNotEmpty()) { "No encoded frames during warmup" }
            val power = context.getSystemService(PowerManager::class.java)
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            result.put("displayHz", display.refreshRate).put("thermalBefore", power.currentThermalStatus).put("batteryCelsiusBefore", temperature())
            val cpuStart = Process.getElapsedCpuTime()
            val start = System.nanoTime()
            validationAfter.set(start)
            SystemClock.sleep(seconds * 1000L)
            val end = System.nanoTime()
            val cpuEnd = Process.getElapsedCpuTime()
            result.put("thermalAfter", power.currentThermalStatus).put("batteryCelsiusAfter", temperature()).put("displayHzAfter", display.refreshRate)
            renderError.get()?.let { throw AssertionError("Renderer failed", it) }
            drainError.get()?.let { throw AssertionError("Encoder drain failed", it) }
            val measured = outputs.filter { it.arrivedNs >= start && it.arrivedNs < end }
            check(measured.size > 1) { "Too few encoded frames" }
            val duration = (end - start) / 1e9
            val gaps = measured.zipWithNext { a, b -> (b.arrivedNs - a.arrivedNs) / 1e6 }.sorted()
            val ptsGaps = measured.zipWithNext { a, b -> (b.ptsUs - a.ptsUs) / 1000.0 }.sorted()
            result.put("elapsedSeconds", duration).put("encodedFrames", measured.size).put("outputFps", measured.size / duration)
                .put("targetPercent", measured.size / duration / scenario.fps * 100)
                .put("appCpuMsPerSecond", (cpuEnd - cpuStart) / duration)
                .put("encodedMbps", measured.sumOf { it.bytes.toLong() } * 8 / duration / 1e6)
                .put("arrivalGapP95Ms", gaps[ceil(gaps.size * 0.95).toInt().coerceIn(1, gaps.size) - 1])
                .put("ptsGapP95Ms", ptsGaps[ceil(ptsGaps.size * 0.95).toInt().coerceIn(1, ptsGaps.size) - 1])
                .put("nonIncreasingPts", measured.zipWithNext().count { (a, b) -> b.ptsUs <= a.ptsUs })
                .put("encoderFormat", codec.outputFormat.toString())
            picture = codec.outputFormat to checkNotNull(validationFrame.get()) { "No keyframe for picture validation" }
        } finally {
            // Keep draining while the renderer finishes its final surface operation.
            try {
                transform?.close()
            } finally {
                try {
                    screen?.let(::closeComposition)
                } finally {
                    draining.set(false)
                    drain?.join(5000)
                    check(drain?.isAlive != true) { "Encoder drain did not stop" }
                    if (started) codec.stop()
                    codec.release()
                    input?.release()
                    bitmaps.forEach { it.recycle() }
                }
            }
        }
        result.put("decodedPicture", validatePicture(checkNotNull(picture)))
    }

    private fun validatePicture(picture: Pair<MediaFormat, ByteArray>): JSONObject {
        val encoded = picture.first
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encoded.getInteger(MediaFormat.KEY_WIDTH), encoded.getInteger(MediaFormat.KEY_HEIGHT)).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                for (key in listOf("csd-0", "csd-1")) encoded.getByteBuffer(key)?.let { setByteBuffer(key, it.duplicate()) }
            }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            val input = decoder.dequeueInputBuffer(5_000_000)
            check(input >= 0)
            decoder.getInputBuffer(input)!!.put(picture.second)
            decoder.queueInputBuffer(input, 0, picture.second.size, 0, 0)
            val eos = decoder.dequeueInputBuffer(5_000_000)
            check(eos >= 0)
            decoder.queueInputBuffer(eos, 0, 0, 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    val image = decoder.getOutputImage(index)
                    if (image != null) {
                        val result =
                            image.use {
                                val crop = it.cropRect
                                check(crop.width() == format.getInteger(MediaFormat.KEY_WIDTH) && crop.height() == format.getInteger(MediaFormat.KEY_HEIGHT)) { "Decoded dimensions differ" }
                                val plane = it.planes[0]
                                val values =
                                    (0 until 64).map { sample ->
                                        val x = crop.left + (sample % 8 + 1) * crop.width() / 9
                                        val y = crop.top + (sample / 8 + 1) * crop.height() / 9
                                        plane.buffer.get(plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
                                    }
                                check(values.max() - values.min() >= 32) { "Encoded picture is uniform; check composition readiness" }
                                JSONObject().put("width", crop.width()).put("height", crop.height()).put("minLuma", values.min()).put("maxLuma", values.max())
                            }
                        decoder.releaseOutputBuffer(index, false)
                        return result
                    }
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            error("Decoder did not return a picture")
        } finally {
            decoder.release()
        }
    }

    private fun closeComposition(screen: Screen) {
        if (screen is VkScreen) {
            screen.dispose()
            return
        }
        // ThreadScreen.dispose is asynchronous. Only the benchmark joins its worker,
        // outside the measurement window, before recycling the shared source bitmaps.
        val getter = screen.javaClass.getDeclaredMethod("getHandler").apply { isAccessible = true }
        val handler = getter.invoke(screen) as android.os.Handler
        screen.dispose()
        val disposed = CountDownLatch(1)
        check(
            handler.post {
                handler.looper.quitSafely()
                disposed.countDown()
            },
        )
        check(disposed.await(5, TimeUnit.SECONDS))
        handler.looper.thread.join(5000)
        check(!handler.looper.thread.isAlive)
    }

    private fun temperature(): Double =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
}
