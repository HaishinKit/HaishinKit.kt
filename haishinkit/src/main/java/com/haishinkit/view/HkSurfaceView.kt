package com.haishinkit.view

import android.content.Context
import android.util.AttributeSet
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.Stream
import com.haishinkit.media.StreamView

/**
 * A view that displays a video content of a [Stream] object which uses [SurfaceView].
 */
class HkSurfaceView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr, defStyleRes), StreamView {
    override var videoGravity: VideoGravity
        get() = pixelTransform.videoGravity
        set(value) {
            pixelTransform.videoGravity = value
        }

    override var frameRate: Int
        get() = pixelTransform.frameRate
        set(value) {
            pixelTransform.frameRate = value
        }

    override var videoEffect: VideoEffect
        get() = pixelTransform.videoEffect
        set(value) {
            pixelTransform.videoEffect = value
        }

    private val pixelTransform: PixelTransform by lazy { PixelTransform.create(context) }

    private var stream: Stream? = null
        set(value) {
            field?.view = null
            field = value
            field?.view = this
            pixelTransform.screen = value?.screen
        }

    init {
        holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    pixelTransform.imageExtent = Size(width, height)
                    pixelTransform.surface = holder.surface
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    pixelTransform.imageExtent = Size(width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    pixelTransform.surface = null
                }
            },
        )
    }

    override fun attachStream(stream: Stream?) {
        this.stream = stream
    }

    private companion object {
        private var TAG = HkSurfaceView::class.java.simpleName
    }
}
