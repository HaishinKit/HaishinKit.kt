package com.haishinkit.vqmetrix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CapturedReference(
    val spec: ReferenceSpec,
    val source: String,
    val captureTimestampsNs: List<Long>,
    val range: String,
    val matrix: String,
    val sensorOrientation: Int = 0,
)

class CameraReference(private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun record(
        file: File,
        config: BatchConfig,
        progress: (Int, ReferenceSpec, ByteArray) -> Unit,
    ): CapturedReference =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id =
                manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: manager.cameraIdList.firstOrNull() ?: error("No camera is available")
            val characteristics = manager.getCameraCharacteristics(id)
            val sizes =
                checkNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                    .getOutputSizes(ImageFormat.YUV_420_888)
            val size =
                sizes.filter { it.width <= config.width && it.height <= config.height && it.width % 2 == 0 && it.height % 2 == 0 }
                    .maxByOrNull { it.width * it.height } ?: error("No compatible YUV camera size")
            val spec = ReferenceSpec(size.width, size.height, config.fps, config.fps * config.seconds)
            val thread = HandlerThread("VQMetrix-camera").apply { start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            val completion = CompletableDeferred<CapturedReference>()
            val finished = AtomicBoolean(false)
            val timestamps = ArrayList<Long>()
            val writer = Y4mWriter(file, spec, "unspecified")
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            reader.setOnImageAvailableListener({ source ->
                val image =
                    try {
                        source.acquireNextImage()
                    } catch (_: IllegalStateException) {
                        null
                    }
                image?.use {
                    if (finished.get()) return@use
                    try {
                        require(it.cropRect.width() == spec.width && it.cropRect.height() == spec.height) { "Camera crop changed" }
                        if (timestamps.isNotEmpty()) require(it.timestamp > timestamps.last()) { "Camera timestamps must increase" }
                        if (timestamps.isNotEmpty() && it.timestamp - timestamps.last() < 900_000_000L / spec.fps) return@use
                        val frame = YuvImages.read(it)
                        writer.append(frame)
                        timestamps.add(it.timestamp)
                        progress(timestamps.size, spec, frame)
                        if (timestamps.size == spec.frames && finished.compareAndSet(false, true)) {
                            completion.complete(
                                CapturedReference(
                                    spec,
                                    "camera:$id",
                                    timestamps.toList(),
                                    "unspecified",
                                    "unspecified",
                                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                                ),
                            )
                        }
                    } catch (failure: Throwable) {
                        finished.set(true)
                        completion.completeExceptionally(failure)
                    }
                }
            }, handler)
            try {
                withTimeout((config.seconds + 15) * 1000L) {
                    device =
                        suspendCancellableCoroutine { continuation ->
                            manager.openCamera(
                                id,
                                object : CameraDevice.StateCallback() {
                                    override fun onOpened(camera: CameraDevice) {
                                        if (continuation.isActive) continuation.resume(camera) { _, value, _ -> value.close() } else camera.close()
                                    }

                                    override fun onDisconnected(camera: CameraDevice) {
                                        camera.close()
                                        val failure = IllegalStateException("Camera disconnected")
                                        if (continuation.isActive) continuation.resumeWithException(failure)
                                        completion.completeExceptionally(failure)
                                    }

                                    override fun onError(
                                        camera: CameraDevice,
                                        error: Int,
                                    ) {
                                        camera.close()
                                        val failure = IllegalStateException("Camera error $error")
                                        if (continuation.isActive) continuation.resumeWithException(failure)
                                        completion.completeExceptionally(failure)
                                    }
                                },
                                handler,
                            )
                        }
                    val camera = checkNotNull(device)
                    session =
                        suspendCancellableCoroutine { continuation ->
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(
                                listOf(reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(value: CameraCaptureSession) {
                                        if (continuation.isActive) continuation.resume(value) { _, active, _ -> active.close() } else value.close()
                                    }

                                    override fun onConfigureFailed(value: CameraCaptureSession) {
                                        value.close()
                                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera session failed"))
                                    }
                                },
                                handler,
                            )
                        }
                    val request =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(reader.surface)
                            val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                            val range = ranges.filter { it.contains(config.fps) }.minByOrNull { it.upper - it.lower }
                            if (range != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        }
                    checkNotNull(session).setRepeatingRequest(request.build(), null, handler)
                    completion.await()
                }
            } finally {
                finished.set(true)
                runCatching { session?.stopRepeating() }
                session?.close()
                device?.close()
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                // Drain any callback still writing a frame before closing its file.
                thread.quitSafely()
                thread.join(3000)
                writer.close()
            }
        }
}
