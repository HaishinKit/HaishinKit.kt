package com.haishinkit.media.source

import android.annotation.SuppressLint
import android.content.Context
import android.view.Surface
import com.haishinkit.media.MediaMixer
import com.haishinkit.screen.VideoScreenObject

/**
 * A video source that captures a camera by the Camera2 API.
 */
@Suppress("UNUSED")
class Camera2Source(
    private val context: Context,
    val cameraId: String = DEFAULT_CAMERA_ID,
) : VideoSource,
    VideoScreenObject.OnSurfaceChangedListener {
    var isTorchEnabled: Boolean = false
        set(value) {
            output?.setTorchEnabled(value)
            field = value
        }

    /**
     * The video screen object.
     */
    override val video: VideoScreenObject by lazy {
        VideoScreenObject().apply {
            isRotatesWithContent = true
            listener = this@Camera2Source
        }
    }

    private var output: Camera2Output? = null
        set(value) {
            if (field == value) return
            field?.close()
            field = value
        }

    /**
     * Opens the camera with camera2 api.
     */
    @SuppressLint("MissingPermission")
    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        val output = Camera2Output(context, this, cameraId)
        this.output = output
        return output.open().onSuccess {
            video.imageOrientation = output.imageOrientation
            output.setTorchEnabled(isTorchEnabled)
            output.getCameraSize(mixer.screen.frame)?.let {
                video.videoSize = it
            }
        }
    }

    override suspend fun close(): Result<Unit> = output?.close() ?: Result.success(Unit)

    override fun onSurfaceChanged(surface: Surface?) {
        surface?.let { output?.createCaptureSession(it) }
    }

    private companion object {
        private const val DEFAULT_CAMERA_ID = "0"
        private val TAG = Camera2Source::class.java.simpleName
    }
}
