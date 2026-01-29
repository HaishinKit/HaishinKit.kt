package com.haishinkit.screen

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.Matrix
import android.util.Log
import android.util.Size
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.util.aspectRatio
import com.haishinkit.util.swap

/**
 * An object that manages offscreen rendering a video source.
 */
@Suppress("MemberVisibilityCanBePrivate")
open class VideoScreenObject(
    target: Int = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
) : ScreenObject(target),
    SurfaceTexture.OnFrameAvailableListener {
    override val type: String = TYPE

    /**
     * Specifies the surface that is an input source.
     */
    open var surface: Surface? = null
        set(value) {
            if (field == value) return
            field = value
            listener?.onSurfaceChanged(value)
        }

    /**
     * Specifies the listener to be invoked when a surface changed.
     */
    var listener: OnSurfaceChangedListener? = null

    /**
     * Specifies the videoGravity how the displays the visual content.
     */
    var videoGravity: VideoGravity = VideoGravity.RESIZE_ASPECT_FILL
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * Specifies the imageOrientation that describe the image orientation.
     */
    var imageOrientation: ImageOrientation = ImageOrientation.UP
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * Specifies the videoSize that describe the video source.
     */
    open var videoSize = Size(0, 0)
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * Specifies whether displayed images rotates(true), or not(false).
     */
    var isRotatesWithContent: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * Specifies the deviceOrientation that describe the physical orientation of the device.
     */
    var deviceOrientation: Int = Surface.ROTATION_0
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    override var isVisible: Boolean
        get() = super.isVisible && isFrameAvailable
        set(value) {
            super.isVisible = value
        }

    override var elements: Map<String, String>
        get() = emptyMap()
        set(value) {}

    private var isFrameAvailable = false

    override fun layout(renderer: Renderer) {
        super.layout(renderer)

        var degrees =
            when (imageOrientation) {
                ImageOrientation.UP -> 0
                ImageOrientation.DOWN -> 180
                ImageOrientation.LEFT -> 90
                ImageOrientation.RIGHT -> 270
                ImageOrientation.UP_MIRRORED -> 0
                ImageOrientation.DOWN_MIRRORED -> 180
                ImageOrientation.LEFT_MIRRORED -> 270
                ImageOrientation.RIGHT_MIRRORED -> 90
            }

        if (isRotatesWithContent) {
            degrees +=
                when (deviceOrientation) {
                    0 -> 0
                    1 -> 270
                    2 -> 180
                    3 -> 90
                    else -> 0
                }
        }

        if (degrees.rem(180) == 0 && (imageOrientation == ImageOrientation.RIGHT || imageOrientation == ImageOrientation.RIGHT_MIRRORED)) {
            degrees += 180
        }

        Matrix.setIdentityM(matrix, 0)

        if (target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
            matrix[5] = matrix[5] * -1
            Matrix.rotateM(matrix, 0, -degrees.toFloat(), 0f, 0f, 1f)
        }

        val swapped = degrees == 90 || degrees == 270
        val newVideoSize = videoSize.swap(swapped)
        when (videoGravity) {
            VideoGravity.RESIZE -> {
                // no op
            }

            VideoGravity.RESIZE_ASPECT -> {
                var x: Float
                var y: Float
                val iRatio = bounds.width().toFloat() / bounds.height().toFloat()
                val fRatio = newVideoSize.aspectRatio
                if (iRatio < fRatio) {
                    x = 1f
                    y = newVideoSize.height.toFloat() / newVideoSize.width.toFloat() * iRatio
                    if (swapped) {
                        x = y
                        y = 1f
                    }
                } else {
                    x = newVideoSize.width.toFloat() / newVideoSize.height.toFloat() / iRatio
                    y = 1f
                    if (swapped) {
                        y = x
                        x = 1f
                    }
                }
                Matrix.scaleM(
                    matrix,
                    0,
                    x,
                    y,
                    1f,
                )
            }

            VideoGravity.RESIZE_ASPECT_FILL -> {
                var x: Float
                var y: Float
                val iRatio = bounds.width().toFloat() / bounds.height().toFloat()
                val fRatio = newVideoSize.aspectRatio
                if (iRatio < fRatio) {
                    x = bounds.height().toFloat() / bounds.width().toFloat() * fRatio
                    y = 1f
                    if (swapped) {
                        y = x
                        x = 1f
                    }
                } else {
                    x = 1f
                    y = bounds.width().toFloat() / bounds.height().toFloat() / fRatio
                    if (swapped) {
                        x = y
                        y = 1f
                    }
                }
                Matrix.scaleM(
                    matrix,
                    0,
                    x,
                    y,
                    1f,
                )
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "$this => matrix: ${matrix.joinToString()}, imageOrientation=$imageOrientation, deviceOrientation=$deviceOrientation, videoSize=$videoSize",
            )
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        try {
            surfaceTexture?.updateTexImage()
            isFrameAvailable = true
        } catch (e: RuntimeException) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "", e)
            }
        }
    }

    interface OnSurfaceChangedListener {
        fun onSurfaceChanged(surface: Surface?)
    }

    companion object {
        const val TYPE: String = "video"
        private val TAG = VideoScreenObject::class.java.simpleName
    }
}
