package com.haishinkit.media.source

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.media.MediaMixer

/**
 * A video source that captures a camera by the Camera2 API.
 */
@Suppress("UNUSED")
class Camera2Source(
    private val context: Context,
    val cameraId: String = DEFAULT_CAMERA_ID,
) : VideoSource {
    var isTorchEnabled: Boolean = false
        set(value) {
            output?.setTorchEnabled(value)
            field = value
        }

    override var surface: Surface? = null
        set(value) {
            if (field == value) return
            field = value
            value?.let {
                output?.createCaptureSession(it)
            }
        }

    override var videoSize: Size = Size(0, 0)
        private set

    override val imageOrientation: ImageOrientation
        get() = output?.imageOrientation ?: ImageOrientation.UP

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
            output.getCameraSize(mixer.screen.frame)?.let {
                videoSize = it
            }
            output.setTorchEnabled(isTorchEnabled)
        }
    }

    override suspend fun close(): Result<Unit> = output?.close() ?: Result.success(Unit)

    private companion object {
        private const val DEFAULT_CAMERA_ID = "0"
        private val TAG = Camera2Source::class.java.simpleName
    }
}
