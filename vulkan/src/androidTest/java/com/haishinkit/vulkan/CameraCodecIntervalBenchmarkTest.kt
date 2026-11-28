package com.haishinkit.vulkan

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.os.SystemClock
import android.os.Trace
import android.util.Size
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.screen.Screen
import com.haishinkit.screen.VideoScreenObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Separate camera, Surface producer, encoded-output arrival and output-PTS timing. */
class CameraCodecIntervalBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test(timeout = 1_200_000)
    fun compareCameraIntervals() {
        assumeTrue(arguments.getString("cameraIntervalBenchmark") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 29 && VkPixelTransform.isSupported())
        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Grant camera permission to the test package" }
        check(Trace.isEnabled()) { "Start app-level atrace before the benchmark" }
        val repetitions = arguments.getString("cameraBenchmarkRepetitions", "4").toInt().also { require(it in 1..6) }
        val seconds = arguments.getString("cameraBenchmarkSeconds", "30").toInt().also { require(it in 3..60) }
        val warmup = arguments.getString("cameraBenchmarkWarmupSeconds", "5").toInt().also { require(it in 1..10) }
        require(repetitions * 2 * (seconds + warmup + 3) < 1000)
        val size = Size(1920, 1080)
        val encoder =
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first {
                it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { mime -> mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }
        check(encoder.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities!!.areSizeAndRateSupported(size.width, size.height, 30.0))
        val results = JSONArray()
        val document =
            JSONObject().put("sourceRevision", arguments.getString("benchmarkRevision", "unknown"))
                .put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT).put("codec", encoder.name)
                .put("vulkanBuildType", BuildConfig.BUILD_TYPE).put("coreBuildType", com.haishinkit.BuildConfig.BUILD_TYPE)
                .put("captureSize", JSONArray(listOf(size.width, size.height))).put("targetFps", 30).put("captureFpsRange", JSONArray(listOf(30, 30)))
                .put("repetitions", repetitions).put("warmupSeconds", warmup).put("measurementSeconds", seconds)
                .put("cameraColumns", JSONArray(listOf("callbackBootNs", "sensorNs", "frameNumber", "exposureNs", "frameDurationNs", "aeFpsLower", "aeFpsUpper")))
                .put("outputColumns", JSONArray(listOf("dequeueBootNs", "ptsUs", "bytes")))
                .put("complete", false).put("results", results)
        val activity = instrumentation.startActivitySync(Intent(context, CameraBenchmarkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            repeat(repetitions) { trial ->
                val backends = if (trial % 2 == 0) listOf("OpenGL", "Vulkan") else listOf("Vulkan", "OpenGL")
                for (backend in backends) {
                    val result = measure(encoder.name, backend, size, warmup, seconds).put("repeat", trial + 1)
                    results.put(result)
                    File(context.filesDir, "camera-codec-intervals.json").writeText(document.toString())
                    instrumentation.sendStatus(2, Bundle().apply { putString("camera_benchmark_progress", "$backend trial ${trial + 1}: ${result.getJSONArray("output").length()} encoded frames") })
                    SystemClock.sleep(3000)
                }
            }
            document.put("complete", true)
            File(context.filesDir, "camera-codec-intervals.json").writeText(document.toString())
            emit(document)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun exportLastResult() {
        assumeTrue(arguments.getString("cameraIntervalExport") == "true")
        emit(JSONObject(File(context.filesDir, "camera-codec-intervals.json").readText()))
    }

    private fun emit(document: JSONObject) {
        val results = document.getJSONArray("results")
        val header = JSONObject(document.toString()).apply { remove("results") }
        instrumentation.sendStatus(2, Bundle().apply { putString("camera_benchmark_header", header.toString()) })
        for (index in 0 until results.length()) instrumentation.sendStatus(2, Bundle().apply { putString("camera_benchmark_trial", results.getJSONObject(index).toString()) })
        instrumentation.sendStatus(2, Bundle().apply { putString("camera_benchmark_complete", document.getBoolean("complete").toString()) })
    }

    private fun measure(
        codecName: String,
        backend: String,
        size: Size,
        warmup: Int,
        seconds: Int,
    ): JSONObject {
        val camera = BenchmarkCameraFeed(context, size)
        val codec = MediaCodec.createByCodecName(codecName)
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_OPERATING_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        val output = ConcurrentLinkedQueue<LongArray>()
        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        var drain: Thread? = null
        var input: android.view.Surface? = null
        var screen: Screen? = null
        var transform: PixelTransform? = null
        var started = false
        try {
            camera.startDevice()
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = codec.createInputSurface()
            codec.start()
            started = true
            drain =
                Thread({
                    try {
                        val info = MediaCodec.BufferInfo()
                        while (running.get()) {
                            val index = codec.dequeueOutputBuffer(info, 5_000)
                            if (index >= 0) {
                                if (info.size > 0 && info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                                    check(info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0)
                                    output.add(longArrayOf(SystemClock.elapsedRealtimeNanos(), info.presentationTimeUs, info.size.toLong()))
                                }
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    }
                }, "CameraBenchmarkDrain").apply { start() }
            val selected = if (backend == "Vulkan") VkScreen.create(context) else Screen.createOpenGL(context)
            screen = selected
            if (selected is VkScreen) selected.onError = { failure.compareAndSet(null, it) }
            selected.frame = Rect(0, 0, size.width, size.height)
            selected.addChild(
                VideoScreenObject().apply {
                    frame = Rect(0, 0, size.width, size.height)
                    videoGravity = VideoGravity.RESIZE
                },
            )
            selected.attachVideo(0, camera)
            camera.awaitFrames()
            val ready = CountDownLatch(3)
            selected.registerCallback(
                object : Screen.Callback() {
                    override fun onEnterFrame() {
                        ready.countDown()
                    }
                },
            )
            check(ready.await(5, TimeUnit.SECONDS))
            transform =
                (if (backend == "Vulkan") VkPixelTransform.create(context) else PixelTransform.createOpenGL(context)).apply {
                    imageExtent = size
                    frameRate = 30
                    videoEffect = BicubicVideoEffect()
                    videoGravity = VideoGravity.RESIZE
                    surface = input
                    this.screen = selected
                }
            SystemClock.sleep(warmup * 1000L)
            check(output.isNotEmpty()) { "No encoded camera frames" }
            val power = context.getSystemService(PowerManager::class.java)
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val result =
                JSONObject().put("backend", backend).put("cameraId", camera.cameraId).put("sensorTimestampSource", camera.timestampSource)
                    .put("thermalBefore", power.currentThermalStatus).put("batteryCelsiusBefore", temperature()).put("displayHzBefore", display.refreshRate)
            val begin = SystemClock.elapsedRealtimeNanos()
            SystemClock.sleep(seconds * 1000L)
            val end = SystemClock.elapsedRealtimeNanos()
            result.put("beginBootNs", begin).put("endBootNs", end).put("thermalAfter", power.currentThermalStatus)
                .put("batteryCelsiusAfter", temperature()).put("displayHzAfter", display.refreshRate).put("encoderFormat", codec.outputFormat.toString())
            failure.get()?.let { throw AssertionError("Output failed", it) }
            camera.error.get()?.let { throw AssertionError("Camera failed", it) }
            transform.close()
            transform = null
            camera.stop()
            running.set(false)
            drain.join(5000)
            check(!drain.isAlive)
            failure.get()?.let { throw AssertionError("Output drain failed", it) }
            val captures = camera.captures.filter { it[0] in begin until end }
            val frames = output.filter { it[0] in begin until end }
            check(captures.size > 2 && frames.size > 2)
            result.put("camera", JSONArray(captures.map { JSONArray(it.toList()) })).put("output", JSONArray(frames.map { JSONArray(it.toList()) }))
            return result
        } finally {
            try {
                transform?.close()
            } finally {
                try {
                    camera.stop()
                } finally {
                    try {
                        screen?.let(::closeComposition)
                    } finally {
                        running.set(false)
                        drain?.join(5000)
                        if (started) codec.stop()
                        codec.release()
                        input?.release()
                    }
                }
            }
        }
    }

    private fun closeComposition(screen: Screen) {
        if (screen is VkScreen) {
            screen.dispose()
            return
        }
        val handler = screen.javaClass.getDeclaredMethod("getHandler").apply { isAccessible = true }.invoke(screen) as android.os.Handler
        screen.dispose()
        val done = CountDownLatch(1)
        check(
            handler.post {
                handler.looper.quitSafely()
                done.countDown()
            },
        )
        check(done.await(5, TimeUnit.SECONDS))
        handler.looper.thread.join(5000)
        check(!handler.looper.thread.isAlive)
    }

    private fun temperature(): Double =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
}
