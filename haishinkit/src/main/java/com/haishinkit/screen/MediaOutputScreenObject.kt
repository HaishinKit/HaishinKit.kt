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
 * Renders another media source's video composition inside a screen.
 *
 * For RTMP playback, register the RTMP session factory first. Run the suspending `session.connect()`
 * call from a coroutine. The example assumes an existing mixer and Android context.
 *
 * ```kotlin
 * StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)
 * val session = StreamSession.Builder(context, Uri.parse("rtmps://example.com/live/stream"))
 *     .setMode(StreamSession.Mode.PLAYBACK)
 *     .build()
 * val video = MediaOutputScreenObject(context)
 * video.frame = Rect(0, 0, 160, 90)
 * video.videoSize = Size(1600, 900)
 * session.stream.registerOutput(video)
 * mixer.screen.addChild(video)
 * session.connect().getOrThrow()
 * ```
 *
 * When finished, close the session, unregister the output, and remove the object from the screen.
 */
@Suppress("UNUSED")
class MediaOutputScreenObject(
    context: Context,
    id: String? = null,
) : VideoScreenObject(id),
    MediaOutput {
    override var type: String = "media"

    /**
     * The destination surface for rendering the registered source's screen.
     */
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
