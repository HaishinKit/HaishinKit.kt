package com.haishinkit.vulkan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.VideoSource
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fixed-rate Camera2 source with capture-result timing; buffers use the normal Screen input. */
internal class BenchmarkCameraFeed(context: Context, override val videoSize: Size) : VideoSource {
    val captures = ConcurrentLinkedQueue<LongArray>()
    val error = AtomicReference<Throwable?>()
    val cameraId: String
    val timestampSource: Int
    override val imageOrientation = ImageOrientation.UP
    override var surface: Surface? = null
        set(value) {
            field = value
            if (value != null) check(handler.post { configure(value) })
        }

    private val manager = checkNotNull(context.getSystemService(CameraManager::class.java))
    private val thread by lazy { HandlerThread("CameraIntervalFeed").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val opened = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val frames = CountDownLatch(5)
    private val closing = AtomicBoolean(false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    init {
        cameraId = manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        check(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.PRIVATE).contains(videoSize)) { "Camera capture size is unavailable" }
        check(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)!!.contains(Range(30, 30))) { "Camera has no fixed 30 fps range" }
        timestampSource = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: -1
    }

    @SuppressLint("MissingPermission")
    fun startDevice() {
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    opened.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (!closing.get()) error.compareAndSet(null, IllegalStateException("Camera disconnected"))
                    camera.close()
                    opened.countDown()
                }

                override fun onError(
                    camera: CameraDevice,
                    code: Int,
                ) {
                    error.compareAndSet(null, IllegalStateException("Camera error $code"))
                    camera.close()
                    opened.countDown()
                }

                override fun onClosed(camera: CameraDevice) {
                    closed.countDown()
                }
            },
            handler,
        )
        check(opened.await(10, TimeUnit.SECONDS)) { "Camera open timed out" }
        error.get()?.let { throw AssertionError("Camera failed", it) }
        check(device != null)
    }

    fun awaitFrames() {
        check(frames.await(10, TimeUnit.SECONDS)) { "Camera did not produce frames" }
        error.get()?.let { throw AssertionError("Camera failed", it) }
    }

    fun stop() {
        if (!closing.compareAndSet(false, true)) return
        check(
            handler.post {
                session?.close()
                device?.close()
                if (device == null) closed.countDown()
            },
        )
        check(closed.await(10, TimeUnit.SECONDS)) { "Camera close timed out" }
        thread.quitSafely()
        thread.join(5000)
        check(!thread.isAlive)
    }

    override suspend fun open(mixer: MediaMixer): Result<Unit> = runCatching { startDevice() }

    override suspend fun close(): Result<Unit> = runCatching { stop() }

    @Suppress("DEPRECATION")
    private fun configure(target: Surface) {
        if (closing.get()) return
        try {
            val camera = checkNotNull(device)
            camera.createCaptureSession(
                listOf(target),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (closing.get()) {
                            configured.close()
                            return
                        }
                        session = configured
                        try {
                            val request =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(target)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                                    val modes = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                                    if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                }.build()
                            configured.setRepeatingRequest(
                                request,
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult,
                                    ) {
                                        val sensor = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                                        val fps = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                                        captures.add(longArrayOf(SystemClock.elapsedRealtimeNanos(), sensor, result.frameNumber, result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0, result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0, (fps?.lower ?: -1).toLong(), (fps?.upper ?: -1).toLong()))
                                        frames.countDown()
                                    }

                                    override fun onCaptureFailed(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: CaptureFailure,
                                    ) {
                                        if (!closing.get()) error.compareAndSet(null, IllegalStateException("Capture failed: ${failure.reason}"))
                                    }
                                },
                                handler,
                            )
                        } catch (failure: Exception) {
                            error.compareAndSet(null, failure)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        error.compareAndSet(null, IllegalStateException("Camera session failed"))
                    }
                },
                handler,
            )
        } catch (failure: Exception) {
            error.compareAndSet(null, failure)
        }
    }
}
