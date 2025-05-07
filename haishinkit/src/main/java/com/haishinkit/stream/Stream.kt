package com.haishinkit.stream

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.Size
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.media.MediaLink
import com.haishinkit.media.MediaMixer
import com.haishinkit.rtmp.RtmpStream
import com.haishinkit.screen.Screen
import com.haishinkit.screen.VideoScreenObject
import com.haishinkit.view.StreamView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Stream class is the foundation of a [RtmpStream].
 */
@Suppress("UNUSED")
abstract class Stream(
    private var applicationContext: Context,
) : VideoScreenObject.OnSurfaceChangedListener {
    val hasAudio: Boolean
        get() = mixer?.audioSource != null

    val hasVideo: Boolean
        get() = mixer?.videoSource != null

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

    internal var videoSize: Size
        get() = video.videoSize
        set(value) {
            screen?.frame = Rect(0, 0, value.width, value.height)
            video.videoSize = value
        }

    internal val mediaLink: MediaLink by lazy {
        MediaLink(audioCodec, videoCodec)
    }

    internal val audioCodec by lazy { AudioCodec() }
    internal val videoCodec by lazy { VideoCodec(applicationContext) }

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

    private val video: VideoScreenObject by lazy {
        VideoScreenObject().apply {
            listener = this@Stream
            isRotatesWithContent = false
        }
    }

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
        mixer?.audioSource?.registerAudioCodec(codec)
    }

    /**
     * Unregisters an audio codec instance.
     */
    fun unregisterAudioCodec(codec: AudioCodec) {
        mixer?.audioSource?.unregisterAudioCodec(codec)
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

    @Synchronized
    protected open fun startRunning() {
        if (isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "startRunning()")
        }
        when (mode) {
            Codec.MODE_ENCODE -> {
                mixer?.audioSource?.let {
                    audioCodec.startRunning()
                    it.registerAudioCodec(audioCodec)
                }
                mixer?.videoSource?.let {
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
                mixer?.audioSource?.unregisterAudioCodec(audioCodec)
            }

            Codec.MODE_DECODE -> {
            }
        }
        audioCodec.stopRunning()
        videoCodec.stopRunning()
        isRunning.set(false)
    }

    private companion object {
        private val TAG = Stream::class.java.simpleName
    }
}
