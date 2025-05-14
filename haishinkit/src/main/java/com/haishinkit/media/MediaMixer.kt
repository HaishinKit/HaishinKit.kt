package com.haishinkit.media

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.haishinkit.codec.AudioCodec
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObjectContainer

/**
 * Mixing audio and video for streaming.
 */
@Suppress("UNUSED")
class MediaMixer(
    context: Context,
) : DefaultLifecycleObserver {
    val hasAudio: Boolean
        get() = audioSources.isNotEmpty()

    val hasVideo: Boolean
        get() = videoSources.isNotEmpty()

    /**
     * The offscreen renderer for video output.
     */
    val screen: Screen by lazy {
        Screen.create(context).apply {
            addChild(videoContainer)
        }
    }

    private var videoSources = mutableMapOf<Int, VideoSource>()
    private var audioSources = mutableMapOf<Int, AudioSource>()
    private val videoContainer: ScreenObjectContainer by lazy {
        ScreenObjectContainer()
    }
    private val orientationEventListener: OrientationEventListener by lazy {
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                val windowManager =
                    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.defaultDisplay?.orientation?.let {
                    videoSources.forEach { videoSource ->
                        videoSource.value.video.deviceOrientation = it
                    }
                }
            }
        }
    }

    init {
        orientationEventListener.enable()
    }

    /**
     * Attaches a audio source.
     */
    suspend fun attachAudio(
        channel: Int,
        audio: AudioSource?,
    ): Result<Unit> {
        if (audio != null) {
            audioSources[channel] = audio
            return audio.open(this)
        }
        audioSources.remove(channel)?.close()
        return Result.success(Unit)
    }

    /**
     * Attaches a video source.
     */
    suspend fun attachVideo(
        channel: Int,
        video: VideoSource?,
    ): Result<Unit> {
        if (video != null) {
            videoSources[channel] = video
            return video.open(this).onSuccess {
                videoContainer.addChild(video.video)
            }
        }
        videoSources.remove(channel)?.let {
            videoContainer.removeChild(it.video)
            it.close()
        }
        return Result.success(Unit)
    }

    /**
     * Sets a video effect.
     */
    fun setVideoEffect(
        channel: Int,
        videoEffect: VideoEffect,
    ) {
        videoSources[channel]?.video?.videoEffect = videoEffect
    }

    /**
     * Registers an audio codec instance.
     */
    fun registerAudioCodec(codec: AudioCodec) {
        audioSources.forEach {
            it.value.registerAudioCodec(codec)
        }
    }

    /**
     * Unregisters an audio codec instance.
     */
    fun unregisterAudioCodec(codec: AudioCodec) {
        audioSources.forEach {
            it.value.unregisterAudioCodec(codec)
        }
    }

    /**
     * Disposes the stream of memory management.
     */
    fun dispose() {
        orientationEventListener.disable()
        audioSources.clear()
        videoSources.clear()
        screen.dispose()
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
    }

    private companion object {
        private val TAG = MediaMixer::class.java.simpleName
    }
}
