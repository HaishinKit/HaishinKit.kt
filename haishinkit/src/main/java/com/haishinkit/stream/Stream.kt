package com.haishinkit.stream

import android.content.Context
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.media.MediaMixer
import com.haishinkit.view.StreamView

/**
 * The Stream class is the foundation of a RtmpStream.
 */
@Suppress("UNUSED")
abstract class Stream(
    applicationContext: Context,
) {
    var mixer: MediaMixer? = null
        set(value) {
            videoCodec.pixelTransform.screen = value?.screen
            view?.screen = value?.screen
            field = value
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

    /**
     * Specifies the StreamView object.
     */
    var view: StreamView? = null

    internal val audioCodec by lazy { AudioCodec() }
    internal val videoCodec by lazy { VideoCodec(applicationContext) }

    /**
     * Closes the stream from the server.
     */
    abstract fun close()

    fun attachView(view: StreamView?) {
        this.view = view
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
}
