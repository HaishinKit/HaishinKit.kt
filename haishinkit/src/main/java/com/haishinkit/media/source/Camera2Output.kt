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
import android.util.Size
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.screen.VideoScreenObject
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

internal class Camera2Output(
    val context: Context,
    val source: VideoSource,
    private val cameraId: String,
) : CameraDevice.StateCallback(),
    VideoScreenObject.OnSurfaceChangedListener {
    var isDisconnected: Boolean = false
        private set
    val isTouchSupported: Boolean
        get() {
            return characteristics?.get<Boolean>(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
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
    private val executor = Executors.newSingleThreadExecutor()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.openCamera(cameraId, executor, this)
            } else {
                manager.openCamera(cameraId, this, handler)
            }
        }

    fun close(): Result<Unit> {
        previewRequestBuilder = null
        device = null
        session = null
        return Result.success(Unit)
    }

    fun setTorchEnabled(enable: Boolean) {
        if (!isTouchSupported) return
        val builder = previewRequestBuilder ?: return
        builder.set<Int>(
            CaptureRequest.FLASH_MODE,
            if (enable) {
                CaptureRequest.FLASH_MODE_TORCH
            } else {
                CaptureRequest.FLASH_MODE_OFF
            },
        )
        session?.setRepeatingRequest(
            builder.build(),
            null,
            null,
        )
    }

    fun createCaptureSession(surface: Surface) {
        val device = device ?: return
        previewRequestBuilder =
            device
                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply {
                    addTarget(surface)
                }
        val request = previewRequestBuilder?.build() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputList =
                buildList {
                    add(OutputConfiguration(surface))
                }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputList,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                session.setRepeatingRequest(request, null, null)
                                this@Camera2Output.session = session
                            } catch (e: RuntimeException) {
                                Log.e(TAG, "", e)
                            }
                        }

                        override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                            this@Camera2Output.session = null
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
                            session.setRepeatingRequest(request, null, null)
                            this@Camera2Output.session = session
                        } catch (e: RuntimeException) {
                            Log.e(TAG, "", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        this@Camera2Output.session = null
                    }
                },
                handler,
            )
        }
    }

    override fun onSurfaceChanged(surface: Surface?) {
        surface?.let {
            createCaptureSession(it)
        }
    }

    override fun onOpened(camera: CameraDevice) {
        isDisconnected = false
        device = camera
        continuation?.resume(Result.success(Unit))
        continuation = null
    }

    override fun onDisconnected(camera: CameraDevice) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onDisconnected($camera)")
        }
        close()
        isDisconnected = true
    }

    override fun onError(
        camera: CameraDevice,
        error: Int,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onError($camera, $error)")
        }
        close()
    }

    fun getCameraSize(rect: Rect): Size? {
        val scm = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = scm?.getOutputSizes(SurfaceTexture::class.java)
        return sizes
            ?.filter { size ->
                (rect.width() <= size.width) && (rect.height() <= size.height)
            }?.sortedBy { size -> size.width * size.height }
            ?.get(0) ?: sizes?.get(0)
    }

    companion object {
        private val TAG = Camera2Output::class.java.simpleName
    }
}
