package com.haishinkit.screen

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.RestrictTo
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.media.source.AssetAudioSource
import com.haishinkit.media.source.AssetVideoSource
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.VideoSource
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the video track of a local file as part of a composition using an OS decoder.
 *
 * File, content and android.resource URIs are supported. Attach [audioSource] to a mixer's
 * audio track to mix the asset's audio; it is not played through the local speaker. The
 * source starts when attached to a rendering screen, and detaching stops its decoder. Adding
 * the object again restarts it if playback was still requested. After EOF, call [startReading]
 * explicitly. Call [cancelReading] to stop explicitly, or [close] when finished.
 * Loading/decoding errors are delivered to [onError] on the playback or rendering thread.
 *
 * ```kotlin
 * val clip = AssetScreenObject(context).apply {
 *     frame = android.graphics.Rect(0, 0, 360, 240)
 *     isLooping = true
 *     startReading(videoUri)
 * }
 * mixer.screen.addChild(clip)
 * // Later: mixer.screen.removeChild(clip); clip.close()
 * ```
 */
class AssetScreenObject(context: Context, id: String? = null) : VideoScreenObject(id), AutoCloseable {
    override val type: String = TYPE

    /** Suggested unique audio track ID; attach [audioSource] to this or another free track. */
    val audioTrack: Int = audioIds.getAndDecrement()

    /** Optional audio input synchronized with this object's video and loop boundaries. */
    val audioSource: AudioSource get() = audio

    /** Whether playback restarts at the end of the video track. */
    @Volatile var isLooping: Boolean = false

    /** Whether reading is requested, including preparation before the first frame. */
    val isReading: Boolean get() = requested && !closed

    /** Last submitted frame timestamp within the current pass, in microseconds; zero when stopped. */
    val currentTimeUs: Long get() = synchronized(lock) { reader?.presentationTimeUs ?: 0 }

    /** URI retained in scene snapshots; permissions and playback position are not serialized. */
    @Volatile var sourceUri: Uri? = null
        private set

    /** Reports asynchronous errors. Marshal to your UI thread before updating UI. */
    @Volatile var onError: (Throwable) -> Unit = { Log.w("AssetScreenObject", "Asset playback failed", it) }

    override var elements: Map<String, String>
        get() =
            mapOf(
                "source" to sourceUri?.toString().orEmpty(),
                "looping" to isLooping.toString(),
                "videoGravity" to
                    when (videoGravity) {
                        VideoGravity.RESIZE -> "resize"
                        VideoGravity.RESIZE_ASPECT_FILL -> "resizeAspectFill"
                        else -> "resizeAspect"
                    },
            )
        set(value) {
            isLooping = value["looping"] == "true"
            videoGravity =
                when (value["videoGravity"]) {
                    "resize", "AVLayerVideoGravityResize" -> VideoGravity.RESIZE
                    "resizeAspectFill", "AVLayerVideoGravityResizeAspectFill" -> VideoGravity.RESIZE_ASPECT_FILL
                    else -> VideoGravity.RESIZE_ASPECT
                }
            value["source"]?.takeIf { it.isNotEmpty() }?.let { startReading(Uri.parse(it)) } ?: run {
                cancelReading()
                sourceUri = null
            }
        }

    private val context = context.applicationContext
    private val audio = AssetAudioSource(this.context) { cause -> runCatching { onError(cause) } }
    private val lock = Any()

    @Volatile private var requested = false

    @Volatile private var closed = false
    private var reader: AssetVideoSource? = null
    private var retiring: AssetVideoSource? = null

    init {
        videoGravity = VideoGravity.RESIZE_ASPECT
    }

    /** Requests video playback from the start. Only the OS-provided container/codec support is used. */
    fun startReading(uri: Uri) {
        require(uri.scheme in setOf("file", "content", "android.resource")) { "Use a local file, content or android.resource URI." }
        val previous =
            synchronized(lock) {
                check(!closed) { "AssetScreenObject is closed." }
                sourceUri = uri
                requested = true
                reader.also {
                    if (it != null) retiring = it
                    reader = null
                }
            }
        previous?.cancel()
        invalidateLayout()
    }

    /** Stops reading, retaining the URI for a subsequent scene export or explicit restart. */
    fun cancelReading() {
        val previous =
            synchronized(lock) {
                requested = false
                reader.also {
                    if (it != null) retiring = it
                    reader = null
                }
            }
        previous?.cancel()
        invalidateLayout()
    }

    /** Stops playback permanently and releases the decoder asynchronously without blocking rendering. */
    override fun close() {
        synchronized(lock) { closed = true }
        audio.finish()
        cancelReading()
    }

    /** @suppress Renderer integration, not an application playback API. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun sourceForRendering(): VideoSource? =
        synchronized(lock) {
            if (!requested || closed) return@synchronized null
            if (reader == null) {
                lateinit var next: AssetVideoSource
                next =
                    AssetVideoSource(context, checkNotNull(sourceUri), { isLooping }, predecessor = retiring, audio = audio) { error, cancelled ->
                        val report =
                            synchronized(lock) {
                                if (reader !== next) {
                                    false
                                } else {
                                    reader = null
                                    if (!cancelled) {
                                        requested = false
                                    }
                                    error != null
                                }
                            }
                        invalidateLayout()
                        if (report) runCatching { onError(checkNotNull(error)) }
                    }
                retiring = null
                reader = next
                next.start()
            }
            reader?.takeIf { it.isPrepared && it.isRunning }?.also { videoSize = it.displaySize }
        }

    /** @suppress Detaches a renderer-owned surface while retaining the playback request. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun detachFromRenderer() {
        val previous =
            synchronized(lock) {
                reader.also {
                    if (it != null) retiring = it
                    reader = null
                }
            }
        previous?.cancel()
        textureId = -1
        invalidateLayout()
    }

    /** @suppress Stops a source that the selected rendering backend cannot attach. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun failFromRenderer(cause: Throwable) {
        cancelReading()
        runCatching { onError(cause) }
    }

    companion object {
        private val audioIds = AtomicInteger(-1)
        const val TYPE = "asset"

        /** @suppress Finds renderer-owned movie objects without exception-based casts. */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun findAssets(container: ScreenObjectContainer): List<AssetScreenObject> {
            if (container is com.haishinkit.gles.screen.ThreadScreen) return container.assetObjects()
            val children = container.getChildren()
            return children.filterIsInstance<AssetScreenObject>() + children.filterIsInstance<ScreenObjectContainer>().flatMap { findAssets(it) }
        }

        internal fun hasReadingAssets(screen: Screen): Boolean = findAssets(screen).any { it.isReading }
    }
}
