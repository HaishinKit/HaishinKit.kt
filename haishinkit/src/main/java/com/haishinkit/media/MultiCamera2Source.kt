package com.haishinkit.media

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.VideoScreenObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A video source that captures multi-camera by the Camera2 API.
 */
@RequiresApi(Build.VERSION_CODES.P)
@Suppress("MemberVisibilityCanBePrivate")
class MultiCamera2Source(val context: Context) : VideoSource {
    override var stream: Stream? = null
    override val isRunning = AtomicBoolean(false)
    override val screen: ScreenObjectContainer by lazy {
        ScreenObjectContainer()
    }
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val orientationEventListener: OrientationEventListener? by lazy {
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                val windowManager =
                    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager
                    .defaultDisplay
                    ?.orientation
                    ?.let {
                        outputs.values.forEach { output ->
                            output.video.deviceOrientation = it
                        }
                    }
            }
        }
    }
    private val outputs = mutableMapOf<Int, Camera2Output>()

    /**
     * Opens the camera with camera2 api.
     */
    @SuppressLint("MissingPermission")
    fun open(
        channel: Int,
        position: Int? = null,
    ) {
        val cameraId =
            if (position == null) {
                DEFAULT_CAMERA_ID
            } else {
                getCameraId(position) ?: DEFAULT_CAMERA_ID
            }
        val output = Camera2Output(context, this, cameraId)
        outputs[channel] = output
        output.open()
    }

    /**
     * Closes the camera.
     */
    fun close() {
        outputs.values.forEach { it.close() }
        outputs.clear()
    }

    /**
     * Gets the video screen object by channel.
     */
    fun getVideoByChannel(channel: Int): VideoScreenObject? {
        return outputs[channel]?.video
    }

    override fun startRunning() {
        if (isRunning.get()) return
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        outputs.values.forEach {
            it.video.deviceOrientation = windowManager.defaultDisplay.rotation
        }
        orientationEventListener?.enable()
        isRunning.set(true)
    }

    override fun stopRunning() {
        if (!isRunning.get()) return
        orientationEventListener?.disable()
        isRunning.set(false)
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
        private val TAG = MultiCamera2Source::class.java.simpleName
    }
}
