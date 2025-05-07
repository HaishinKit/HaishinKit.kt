package com.haishinkit.stream

import android.content.Context
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.media.MediaMixer
import com.haishinkit.screen.Screen
import com.haishinkit.view.StreamView

/**
 * The Stream class is the foundation of a RtmpStream.
 */
@Suppress("UNUSED")
abstract class Stream(
    private var applicationContext: Context,
) {
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

    /**
     * The offscreen renderer for video output.
     */
    var screen: Screen? = Screen.create(applicationContext)
        get() {
            return mixer?.screen ?: field
        }

    var mixer: MediaMixer? = null
        private set(value) {
            videoCodec.pixelTransform.screen = value?.screen
            view?.screen = value?.screen
            field = value
        }

    var view: StreamView? = null
        private set

    internal val audioCodec by lazy { AudioCodec() }
    internal val videoCodec by lazy { VideoCodec(applicationContext) }

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
     * Disposes the stream of memory management.
     */
    open fun dispose() {
        view = null
        mixer = null
        audioCodec.dispose()
        videoCodec.dispose()
    }
}
