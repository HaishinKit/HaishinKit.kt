package com.haishinkit.screen

import android.content.Context
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import java.lang.ref.WeakReference

/**
 * An object that manages offscreen rendering a streaming video source.
 *
 * ### Usage
 * ```kotlin
 * val session = StreamSession.Builder(context, Preference.shared.rtmpURL.toUri()).build()
 *
 * val screenSessionObject = MediaOutputScreenObject(context)
 * screenSessionObject.frame.set(0, 0, 160, 90)
 * screenSessionObject.videoSize = Size(1600, 900)
 * playback.stream.registerOutput(screenSessionObject)
 * mixer.screen.addChild(screenSessionObject)
 *
 * session.connect(StreamSession.Method.PLAYBACK)
 * ```
 */
@Suppress("UNUSED")
class MediaOutputScreenObject(
    context: Context,
    id: String? = null
) : VideoScreenObject(id),
    MediaOutput {
    override var type: String = "media"

    var surface: Surface?
        get() {
            return pixelTransform.surface
        }
        set(value) {
            pixelTransform.surface = value
        }

    override var dataSource: WeakReference<MediaOutputDataSource>? = null
        set(value) {
            field = value
            pixelTransform.screen = value?.get()?.screen
        }

    override var elements: Map<String, String>
        get() = emptyMap()
        set(value) {}

    init {
        videoGravity = VideoGravity.RESIZE_ASPECT
    }

    private val pixelTransform: PixelTransform by lazy { PixelTransform.create(context) }

    override fun append(buffer: MediaBuffer) {
    }

    override fun layout(renderer: Renderer) {
        getBounds(bounds)
        pixelTransform.imageExtent = Size(bounds.width(), bounds.height())
        super.layout(renderer)
    }
}
