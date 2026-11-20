package com.haishinkit.vqmetrix

import android.Manifest
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BatchDeviceTest {
    @Test
    fun h264AndHevcProduceExactFrameMapsAndChecksummedPackages() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            for (codec in listOf("h264", "hevc")) {
                val batch = BatchPipeline(context).create(BatchConfig(320, 180, 15, 1, listOf(300_000, 800_000), codec, false), {}, {})
                try {
                    val manifest = JSONObject(File(batch.directory, "manifest.json").readText())
                    assertEquals(15, manifest.getJSONObject("reference").getInt("frameCount"))
                    assertEquals(2, manifest.getJSONArray("variants").length())
                    val performance = manifest.getJSONArray("variants").getJSONObject(0).getJSONObject("performance")
                    assertEquals("offlineReplay", performance.getString("mode"))
                    assertEquals(15, performance.getInt("frameCount"))
                    assertEquals(15, performance.getJSONArray("frameTimings").length())
                    assertTrue(performance.getDouble("wallTimeMs") > 0)
                    assertTrue(performance.getDouble("throughputFps") > 0)
                    assertTrue(performance.getDouble("p95LatencyMs") >= 0)
                    assertTrue(performance.getDouble("appCpuCores") >= 0)
                    assertTrue(performance.getJSONArray("samples").length() >= 2)
                    val files = manifest.getJSONArray("files")
                    for (index in 0 until files.length()) {
                        val description = files.getJSONObject(index)
                        val file = File(batch.directory, description.getString("path"))
                        assertEquals(description.getLong("size"), file.length())
                        assertEquals(description.getString("sha256"), BatchPipeline.sha256(file))
                    }
                    ZipFile(batch.archive).use { zip ->
                        assertNotNull(zip.getEntry("manifest.json"))
                        assertNotNull(zip.getEntry("reference.y4m"))
                        assertNotNull(zip.getEntry("$codec-300000.mp4"))
                    }
                    for (rate in listOf(300_000, 800_000)) {
                        val extractor = MediaExtractor()
                        try {
                            extractor.setDataSource(File(batch.directory, "$codec-$rate.mp4").path)
                            assertEquals(1, extractor.trackCount)
                            val format = extractor.getTrackFormat(0)
                            assertEquals(
                                if (codec == "h264") MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC,
                                format.getString(MediaFormat.KEY_MIME),
                            )
                            extractor.selectTrack(0)
                            var count = 0
                            while (extractor.sampleTime >= 0) {
                                assertEquals(VariantEncoder.pts(count, 15), extractor.sampleTime)
                                count++
                                if (!extractor.advance()) break
                            }
                            assertEquals(15, count)
                        } finally {
                            extractor.release()
                        }
                    }
                } finally {
                    batch.directory.deleteRecursively()
                }
            }
        }

    @Test
    fun mixerSnapshotsUsePublicScreenApiAndEncodeWithoutSdkChanges() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            ActivityScenario.launch<CaptureTestActivity>(Intent(context, CaptureTestActivity::class.java)).use { scenario ->
                lateinit var screen: com.haishinkit.screen.Screen
                scenario.onActivity {
                    screen =
                        com.haishinkit.screen.Screen.createOpenGL(it).apply {
                            frame = android.graphics.Rect(0, 0, 320, 180)
                            addChild(
                                com.haishinkit.screen.ImageScreenObject().apply {
                                    bitmap = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                                    frame = android.graphics.Rect(0, 0, 320, 180)
                                },
                            )
                        }
                }
                kotlinx.coroutines.delay(300)
                try {
                    val batch = BatchPipeline(context).create(BatchConfig(320, 180, 15, 1, listOf(300_000), "h264", false), {}, {}, ScreenReference(screen, "projection"))
                    try {
                        val manifest = JSONObject(File(batch.directory, "manifest.json").readText())
                        val reference = manifest.getJSONObject("reference")
                        assertEquals("mixer-projection", reference.getString("source"))
                        assertEquals(15, reference.getInt("frameCount"))
                        assertEquals("bt709", reference.getString("colorMatrix"))
                        Y4mReader(File(batch.directory, "reference.y4m"), ReferenceSpec(320, 180, 15, 15)).use { reader ->
                            val frame = reader.next()!!
                            assertTrue("Expected opaque white mixer content; got ${frame[0].toInt() and 255}", (frame[0].toInt() and 255) in 233..235)
                            assertEquals(128, frame[320 * 180].toInt() and 255)
                        }
                    } finally {
                        batch.directory.deleteRecursively()
                    }
                } finally {
                    scenario.onActivity { screen.dispose() }
                }
            }
        }

    @Test
    fun cameraReferenceContainsAllCapturedPlanesAndTimestamps() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            val intent = Intent(context, CaptureTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<CaptureTestActivity>(intent).use {
                val file = File(context.cacheDir, "camera-reference.y4m")
                try {
                    val result =
                        CameraReference(
                            context,
                        ).record(file, BatchConfig(640, 480, 15, 1, listOf(500_000), "h264", true)) { _, _, _ -> }
                    assertEquals(result.spec.frames, result.captureTimestampsNs.size)
                    assertTrue(result.captureTimestampsNs.zipWithNext().all { (a, b) -> b > a })
                    Y4mReader(file, result.spec).use { reader ->
                        repeat(result.spec.frames) { assertEquals(result.spec.frameBytes, reader.next()!!.size) }
                        assertNull(reader.next())
                    }
                } finally {
                    file.delete()
                }
            }
        }
}
