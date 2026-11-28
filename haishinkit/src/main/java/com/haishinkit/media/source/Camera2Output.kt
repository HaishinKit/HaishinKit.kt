package com.haishinkit.media.source

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.graphics.ImageOrientation
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

internal class Camera2Output(
    val context: Context,
    val source: Camera2Source,
    private val cameraId: String,
) : CameraDevice.StateCallback() {
    var isDisconnected: Boolean = false
        private set
    val isTorchSupported: Boolean
        get() {
            return characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    val zoomRatioRange: Range<Float>
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    ?: Range(1f, characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            } else {
                Range(1f, characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            }
    val isBackFacing: Boolean
        get() = characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK

    val imageOrientation: ImageOrientation
        get() {
            return when (characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)) {
                0 -> ImageOrientation.UP
                90 -> ImageOrientation.LEFT
                180 -> ImageOrientation.DOWN
                270 -> ImageOrientation.RIGHT
                else -> ImageOrientation.UP
            }
        }
    private val controlLock = Any()
    private var device: CameraDevice? = null
        set(value) {
            if (field == value) return
            field?.close()
            field = value
        }
    private var session: CameraCaptureSession? = null
        set(value) {
            if (field == value) return
            field?.close()
            field = value
        }
    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var characteristics: CameraCharacteristics? = null
    private val handler: Handler by lazy {
        val thread = HandlerThread(TAG)
        thread.start()
        Handler(thread.looper)
    }

    private var continuation: CancellableContinuation<Result<Unit>>? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    @SuppressLint("MissingPermission")
    suspend fun open(): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            this.continuation = continuation
            characteristics = manager.getCameraCharacteristics(cameraId)
            if (source.dynamicRangeMode == DynamicRangeMode.HLG) {
                if (Build.VERSION.SDK_INT < 33 || characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10) != true) {
                    this.continuation = null
                    continuation.resume(Result.failure(IllegalArgumentException("Camera does not support HLG10 capture")))
                    return@suspendCancellableCoroutine
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.openCamera(cameraId, executor, this)
            } else {
                manager.openCamera(cameraId, this, handler)
            }
        }

    fun close(): Result<Unit> =
        synchronized(controlLock) {
            previewRequestBuilder = null
            device = null
            session = null
            return Result.success(Unit)
        }

    fun updateControls() =
        synchronized(controlLock) {
            val builder = previewRequestBuilder ?: return@synchronized
            if (isTorchSupported) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, if (source.isTorchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            val ratio = zoomRatioRange.clamp(source.zoomRatio)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
            } else {
                characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { sensor ->
                    val width = (sensor.width() / ratio).toInt().coerceAtLeast(1)
                    val height = (sensor.height() / ratio).toInt().coerceAtLeast(1)
                    val left = sensor.left + (sensor.width() - width) / 2
                    val top = sensor.top + (sensor.height() - height) / 2
                    builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + width, top + height))
                }
            }
            try {
                session?.setRepeatingRequest(builder.build(), null, null)
            } catch (e: android.hardware.camera2.CameraAccessException) {
                Log.e(TAG, "Unable to submit camera controls: cameraId=$cameraId", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Unable to submit camera controls because the capture session is no longer valid: cameraId=$cameraId", e)
            }
        }

    fun createCaptureSession(surface: Surface) =
        synchronized(controlLock) {
            val device = device ?: return@synchronized
            session = null
            previewRequestBuilder =
                device
                    .createCaptureRequest(if (source.dynamicRangeMode == DynamicRangeMode.HLG) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                    .apply {
                        addTarget(surface)
                    }
            val builder = previewRequestBuilder ?: return@synchronized
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputList =
                    buildList {
                        add(
                            OutputConfiguration(surface).apply {
                                if (Build.VERSION.SDK_INT >= 33 && source.dynamicRangeMode == DynamicRangeMode.HLG) {
                                    dynamicRangeProfile = android.hardware.camera2.params.DynamicRangeProfiles.HLG10
                                }
                            },
                        )
                    }
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputList,
                        executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    synchronized(controlLock) {
                                        if (previewRequestBuilder !== builder) {
                                            session.close()
                                        } else {
                                            this@Camera2Output.session = session
                                            updateControls()
                                        }
                                    }
                                } catch (e: RuntimeException) {
                                    Log.e(TAG, "Unable to start the configured camera capture session: cameraId=$cameraId", e)
                                }
                            }

                            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                synchronized(controlLock) {
                                    if (previewRequestBuilder === builder) this@Camera2Output.session = null
                                }
                            }
                        },
                    ),
                )
            } else {
                val surfaces =
                    buildList {
                        add(surface)
                    }
                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                synchronized(controlLock) {
                                    if (previewRequestBuilder !== builder) {
                                        session.close()
                                    } else {
                                        this@Camera2Output.session = session
                                        updateControls()
                                    }
                                }
                            } catch (e: RuntimeException) {
                                Log.e(TAG, "Unable to start the configured camera capture session: cameraId=$cameraId", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            synchronized(controlLock) {
                                if (previewRequestBuilder === builder) this@Camera2Output.session = null
                            }
                        }
                    },
                    handler,
                )
            }
        }

    override fun onOpened(camera: CameraDevice) {
        isDisconnected = false
        val pending = continuation
        if (pending?.isActive != true) {
            camera.close()
            continuation = null
            return
        }
        device = camera
        pending.resume(Result.success(Unit)) { _, _, _ -> camera.close() }
        continuation = null
    }

    override fun onDisconnected(camera: CameraDevice) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Camera disconnected: cameraId=$cameraId")
        }
        camera.close()
        close()
        isDisconnected = true
        continuation?.resume(Result.failure(IllegalStateException("Camera $cameraId disconnected.")))
        continuation = null
    }

    override fun onError(
        camera: CameraDevice,
        error: Int,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Camera device error: cameraId=$cameraId, errorCode=$error")
        }
        camera.close()
        close()
        continuation?.resume(Result.failure(IllegalStateException("Camera $cameraId failed to open ($error).")))
        continuation = null
    }

    fun getCameraSize(
        rect: Rect,
        maximumSize: Size?,
    ): Size? {
        val scm = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = scm?.getOutputSizes(SurfaceTexture::class.java) ?: return null
        if (maximumSize != null) {
            return sizes.filter { it.width <= maximumSize.width && it.height <= maximumSize.height }
                .maxByOrNull { it.width * it.height }
        }
        return sizes.filter { it.width >= rect.width() && it.height >= rect.height() }
            .minByOrNull { it.width * it.height } ?: sizes.firstOrNull()
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private val TAG = Camera2Output::class.java.simpleName
    }
}
