package com.haishinkit.stream

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.core.util.Pools
import com.haishinkit.BuildConfig
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.media.MediaLink
import com.haishinkit.media.MediaLink.Buffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import com.haishinkit.rtmp.RtmpStream
import com.haishinkit.screen.Screen
import com.haishinkit.screen.VideoScreenObject
import com.haishinkit.view.StreamView
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Stream class is the foundation of a [RtmpStream].
 */
@Suppress("UNUSED")
abstract class Stream(
    private var applicationContext: Context,
) : VideoScreenObject.OnSurfaceChangedListener {
    var hasAudio: Boolean = false
        get() {
            if (mixer == null) {
                return field
            }
            return mixer?.hasAudio == true
        }
        protected set

    var hasVideo: Boolean = false
        get() {
            if (mixer == null) {
                return field
            }
            return mixer?.hasVideo == true
        }
        protected set

    /**
     * The offscreen renderer for video output.
     */
    var screen: Screen? = Screen.create(applicationContext)
        get() {
            return mixer?.screen ?: field
        }

    /**
     * Specifies the video codec settings.
     */
    val videoSetting: VideoCodec.Setting by lazy {
        VideoCodec.Setting(videoCodec)
    }

    /**
     * Specifies the audio codec settings.
     */
    val audioSetting: AudioCodec.Setting by lazy {
        AudioCodec.Setting(audioCodec)
    }

    internal var view: StreamView? = null
        private set

    internal val mediaLink: MediaLink by lazy {
        MediaLink(this)
    }

    protected val audioCodec by lazy { AudioCodec() }

    protected val videoCodec by lazy { VideoCodec(applicationContext) }

    protected var mixer: MediaMixer? = null
        private set(value) {
            videoCodec.pixelTransform.screen = value?.screen
            view?.screen = value?.screen
            field = value
        }

    protected var mode = Codec.MODE_ENCODE
        set(value) {
            if (field == value) return
            audioCodec.mode = value
            videoCodec.mode = value
            field = value
        }

    protected var isRunning = AtomicBoolean(false)

    protected val video: VideoScreenObject by lazy {
        VideoScreenObject().apply {
            listener = this@Stream
            isRotatesWithContent = false
        }
    }

    private var audioBufferPool = Pools.SynchronizedPool<Buffer>(BUFFER_POOL_COUNTS)
    private var videoBufferPool = Pools.SynchronizedPool<Buffer>(BUFFER_POOL_COUNTS)

    /**
     * Attaches a view.
     */
    fun attachView(view: StreamView?) {
        this.view = view
    }

    /**
     * Attaches the media mixer.
     */
    fun attachMediaMixer(mixer: MediaMixer?) {
        this.mixer = mixer
    }

    /**
     * Closes the stream from the server.
     */
    abstract fun close()

    /**
     * Registers an audio codec instance.
     */
    fun registerAudioCodec(codec: AudioCodec) {
        mixer?.registerAudioCodec(codec)
    }

    /**
     * Unregisters an audio codec instance.
     */
    fun unregisterAudioCodec(codec: AudioCodec) {
        mixer?.unregisterAudioCodec(codec)
    }

    /**
     * Disposes the stream of memory management.
     */
    open fun dispose() {
        view = null
        mixer = null
        audioCodec.dispose()
        videoCodec.dispose()
    }

    override fun onSurfaceChanged(surface: Surface?) {
        videoCodec.surface = surface
    }

    internal fun queueOutputBuffer(
        type: MediaType,
        index: Int,
        payload: ByteBuffer?,
        timestamp: Long,
        sync: Boolean,
    ) {
        when (type) {
            MediaType.AUDIO -> {
                mediaLink.queueAudio(
                    (audioBufferPool.acquire() ?: Buffer(type, 0, null, 0, false)).apply {
                        this.index = index
                        this.payload = payload
                        this.timestamp = timestamp
                        this.sync = sync
                    },
                )
            }

            MediaType.VIDEO -> {
                mediaLink.queueVideo(
                    (videoBufferPool.acquire() ?: Buffer(type, 0, null, 0, false)).apply {
                        this.index = index
                        this.payload = payload
                        this.timestamp = timestamp
                        this.sync = sync
                    },
                )
            }
        }
    }

    internal fun releaseOutputBuffer(
        buffer: Buffer,
        render: Boolean,
    ) {
        when (buffer.type) {
            MediaType.AUDIO -> {
                if (audioCodec.isRunning.get()) {
                    audioCodec.codec?.releaseOutputBuffer(buffer.index, false)
                }
                buffer.payload = null
                audioBufferPool.release(buffer)
            }

            MediaType.VIDEO -> {
                if (videoCodec.isRunning.get()) {
                    if (render) {
                        videoCodec.codec?.releaseOutputBuffer(buffer.index, buffer.timestamp * 1000)
                    } else {
                        videoCodec.codec?.releaseOutputBuffer(buffer.index, false)
                    }
                }
                videoBufferPool.release(buffer)
            }
        }
    }

    @Synchronized
    protected open fun startRunning() {
        if (isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "startRunning()")
        }
        when (mode) {
            Codec.MODE_ENCODE -> {
                if (mixer?.hasVideo == true) {
                    videoCodec.startRunning()
                }
            }

            Codec.MODE_DECODE -> {
                screen?.addChild(video)
                mediaLink.startRunning()
            }
        }
        isRunning.set(true)
    }

    @Synchronized
    protected open fun stopRunning() {
        if (!isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stopRunning()")
        }
        when (mode) {
            Codec.MODE_ENCODE -> {
                mixer?.unregisterAudioCodec(audioCodec)
            }

            Codec.MODE_DECODE -> {
                mediaLink.stopRunning()
                screen?.removeChild(video)
                hasAudio = false
                hasVideo = false
            }
        }
        audioCodec.listener = null
        audioCodec.stopRunning()
        videoCodec.listener = null
        videoCodec.stopRunning()
        isRunning.set(false)
    }

    private companion object {
        private const val BUFFER_POOL_COUNTS = 16
        private val TAG = Stream::class.java.simpleName
    }
}
