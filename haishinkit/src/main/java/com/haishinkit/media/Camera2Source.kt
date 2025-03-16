package com.haishinkit.media

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.OrientationEventListener
import android.view.WindowManager
import com.haishinkit.BuildConfig
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.VideoScreenObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A video source that captures a camera by the Camera2 API.
 */
@Suppress("MemberVisibilityCanBePrivate")
class Camera2Source(private val context: Context) : VideoSource {
    /**
     * The video screen object.
     */
    val video: VideoScreenObject?
        get() = output?.video
    override var stream: Stream? = null
    override val isRunning = AtomicBoolean(false)
    override val screen: ScreenObjectContainer by lazy {
        ScreenObjectContainer()
    }
    private var output: Camera2Output? = null
    private var manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val orientationEventListener: OrientationEventListener? by lazy {
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                val windowManager =
                    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager
                    .defaultDisplay
                    ?.orientation
                    ?.let { output?.video?.deviceOrientation = it }
            }
        }
    }

    /**
     * Opens the camera with camera2 api.
     */
    @SuppressLint("MissingPermission")
    fun open(position: Int? = null) {
        val cameraId =
            if (position == null) {
                DEFAULT_CAMERA_ID
            } else {
                getCameraId(position) ?: DEFAULT_CAMERA_ID
            }
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        output = Camera2Output(context, this, cameraId)
        output?.video?.deviceOrientation = windowManager.defaultDisplay.rotation
        output?.open()
    }

    /**
     * Closes the camera.
     */
    fun close() {
        output?.close()
    }

    /**
     * Switches an using camera front or back.
     */
    fun switchCamera() {
        val facing = output?.facing
        val expect =
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                CameraCharacteristics.LENS_FACING_BACK
            } else {
                CameraCharacteristics.LENS_FACING_FRONT
            }
        open(expect)
    }

    override fun startRunning() {
        if (isRunning.get()) return
        orientationEventListener?.enable()
        isRunning.set(true)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, this::startRunning.name)
        }
    }

    override fun stopRunning() {
        if (!isRunning.get()) return
        orientationEventListener?.disable()
        isRunning.set(false)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, this::startRunning.name)
        }
    }

    private fun getCameraId(facing: Int): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                return id
            }
        }
        return null
    }

    private companion object {
        private const val DEFAULT_CAMERA_ID = "0"
        private val TAG = Camera2Source::class.java.simpleName
    }
}
