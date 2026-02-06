package com.haishinkit.view

import android.content.Context
import android.util.AttributeSet
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutputDataSource
import java.lang.ref.WeakReference

/**
 * A view that displays a video content of a [MediaMixer] object which uses [SurfaceView].
 */
class HkSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        defStyleRes: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr, defStyleRes),
        StreamView {
        override var dataSource: WeakReference<MediaOutputDataSource>? = null
            set(value) {
                field = value
                pixelTransform.screen = value?.get()?.screen
            }

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

        override fun setBackgroundColor(color: Int) {
            pixelTransform.backgroundColor = color
        }

        override fun append(buffer: MediaBuffer) {
        }

        private companion object {
            @Suppress("unused")
            private val TAG = HkSurfaceView::class.java.simpleName
        }
    }
