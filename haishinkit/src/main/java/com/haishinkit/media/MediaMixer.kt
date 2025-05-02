package com.haishinkit.media

import android.content.Context
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen

/**
 * Mixing audio and video for streaming.
 */
@Suppress("UNUSED")
class MediaMixer(
    applicationContext: Context,
) {
    /**
     * The offscreen renderer for video output.
     */
    val screen: Screen by lazy {
        Screen.create(applicationContext)
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
        audioSource = audio
    }

    /**
     * Attaches a video source.
     */
    fun attachVideo(video: VideoSource?) {
        this.videoSource = video
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
