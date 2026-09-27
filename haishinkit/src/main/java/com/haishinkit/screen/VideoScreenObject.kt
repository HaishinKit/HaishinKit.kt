package com.haishinkit.screen

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
 * Renders a video track as part of an offscreen composition.
 */
@Suppress("MemberVisibilityCanBePrivate")
open class VideoScreenObject(
    id: String? = null,
    target: Int = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
) : ScreenObject(id, target) {
    private interface Keys {
        companion object {
            const val TRACK = "track"
        }
    }

    override val type: String = TYPE

    /**
     * The video track to render, matching the track passed to `MediaMixer.attachVideo`. Defaults to 0.
     */
    var track: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * How the video is scaled to fit its bounds.
     */
    var videoGravity: VideoGravity = VideoGravity.RESIZE_ASPECT_FILL
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * The rotation and mirroring of the source image.
     */
    var imageOrientation: ImageOrientation = ImageOrientation.UP
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * The source video dimensions, in pixels.
     */
    open var videoSize = Size(0, 0)
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * Whether to apply [deviceOrientation] when rotating the video.
     */
    var isRotatesWithContent: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * The display rotation, expressed as a `Surface.ROTATION_*` constant.
     */
    var deviceOrientation: Int = Surface.ROTATION_0
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    override var elements: Map<String, String>
        get() {
            return buildMap {
                put(Keys.TRACK, track.toString())
            }
        }
        set(value) {
            track = value[Keys.TRACK]?.toInt() ?: 0
        }

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

    companion object {
        const val TYPE: String = "video"
        private val TAG = VideoScreenObject::class.java.simpleName
    }
}
