package com.haishinkit.media.source

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
import java.util.concurrent.Executors

internal class Camera2Output(
    val applicationContext: Context,
    val source: VideoSource,
    private val cameraId: String,
) : CameraDevice.StateCallback(),
    VideoScreenObject.OnSurfaceChangedListener {
    val facing: Int?
        get() = characteristics?.get(CameraCharacteristics.LENS_FACING)

    val video: VideoScreenObject by lazy {
        VideoScreenObject().apply {
            isRotatesWithContent = true
            listener = this@Camera2Output
        }
    }

    var isDisconnected: Boolean = false
        private set

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
        applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor = Executors.newSingleThreadExecutor()
    private var characteristics: CameraCharacteristics? = null
    private val imageOrientation: ImageOrientation
        get() {
            return when (characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)) {
                0 -> ImageOrientation.UP
                90 -> ImageOrientation.LEFT
                180 -> ImageOrientation.DOWN
                270 -> ImageOrientation.RIGHT
                else -> ImageOrientation.UP
            }
        }

    private val handler: Handler by lazy {
        val thread = HandlerThread(TAG)
        thread.start()
        Handler(thread.looper)
    }

    @SuppressLint("MissingPermission")
    fun open() {
        characteristics = manager.getCameraCharacteristics(cameraId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.openCamera(cameraId, executor, this)
        } else {
            manager.openCamera(cameraId, this, handler)
        }
    }

    fun close() {
        source.screen.removeChild(video)
        device = null
        session = null
    }

    override fun onSurfaceChanged(surface: Surface?) {
        surface?.let {
            createCaptureSession(it)
        }
    }

    override fun onOpened(camera: CameraDevice) {
        isDisconnected = false
        device = camera
        source.mixer?.screen?.frame?.let {
            if (it.height() <= it.width()) {
                getCameraSize(it.width(), it.height())?.let { size ->
                    video.videoSize = size
                }
            } else {
                getCameraSize(it.height(), it.width())?.let { size ->
                    video.videoSize = size
                }
            }
        }
        video.imageOrientation = imageOrientation
        source.screen.addChild(video)
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

    private fun getCameraSize(
        width: Int?,
        height: Int?,
    ): Size? {
        val scm = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = scm?.getOutputSizes(SurfaceTexture::class.java)
        if (width == null || height == null) {
            return sizes?.get(0)
        }
        return sizes
            ?.filter { size ->
                (width <= size.width) && (height <= size.height)
            }?.sortedBy { size -> size.width * size.height }
            ?.get(0) ?: sizes?.get(0)
    }

    private fun createCaptureSession(surface: Surface) {
        val device = device ?: return
        val request =
            device
                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply {
                    addTarget(surface)
                }.build()
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

    companion object {
        private val TAG = Camera2Output::class.java.simpleName
    }
}
