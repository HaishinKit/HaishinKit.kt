package com.haishinkit.media

import android.content.Context
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.stream.Stream

/**
 * Mixing audio and video for streaming.
 */
@Suppress("UNUSED")
class MediaMixer(applicationContext: Context) {
    /**
     * The offscreen renderer for video output.
     */
    val screen: Screen by lazy {
        val screen =
            Screen.create(applicationContext)
        screen
    }

    /**
     * The current audio source object.
     */
    var audioSource: AudioSource? = null
        internal set(value) {
            field?.stopRunning()
            field?.mixer = null
            field = value
            field?.mixer = this
            field?.startRunning()
        }

    /**
     * The current video source object.
     */
    var videoSource: VideoSource? = null
        internal set(value) {
            field?.stopRunning()
            screen.removeChild(field?.screen)
            field?.mixer = null
            field = value
            field?.mixer = this
            screen.addChild(field?.screen)
            field?.startRunning()
        }

    /**
     * Attaches an audio source.
     */
    fun attachAudio(audio: AudioSource?) {
        if (audio == null) {
            this.audioSource = null
            return
        }
        this.audioSource = audio
    }

    /**
     * Attaches a video source.
     */
    fun attachVideo(video: VideoSource?) {
        if (video == null) {
            this.videoSource = null
            return
        }
        this.videoSource = video
    }

    /**
     * Registers a stream for output.
     */
    fun registerStream(stream: Stream) {
        stream.mixer = this
    }

    /**
     * Unregister a stream for output.
     */
    fun unregisterStream(stream: Stream) {
        stream.mixer = null
    }

    /**
     * Disposes the stream of memory management.
     */
    fun dispose() {
        audioSource = null
        videoSource = null
        screen.dispose()
    }

    private companion object {
        private val TAG = MediaMixer::class.java.simpleName
    }
}
