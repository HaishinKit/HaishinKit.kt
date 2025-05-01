package com.haishinkit.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.Stream
import com.haishinkit.media.StreamView

/**
 * A view that displays a video content of a [Stream] object which uses [TextureView].
 */
class HkTextureView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) :
    TextureView(context, attrs, defStyleAttr, defStyleRes),
    StreamView,
    TextureView.SurfaceTextureListener {
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
        surfaceTextureListener = this
    }

    override fun attachStream(stream: Stream?) {
        this.stream = stream
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        pixelTransform.imageExtent = Size(width, height)
        pixelTransform.surface = Surface(surface)
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        pixelTransform.imageExtent = Size(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        pixelTransform.surface = null
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    private companion object {
        private val TAG = HkTextureView::class.java.simpleName
    }
}
