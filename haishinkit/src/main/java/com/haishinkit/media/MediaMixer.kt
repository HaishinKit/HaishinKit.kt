package com.haishinkit.media

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObjectContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext

/**
 * Mixing audio and video for streaming.
 */
@Suppress("UNUSED")
class MediaMixer(
    context: Context,
) : MediaOutputDataSource,
    CoroutineScope,
    DefaultLifecycleObserver {
    override val hasAudio: Boolean
        get() = audioSources.isNotEmpty()

    override val hasVideo: Boolean
        get() = videoSources.isNotEmpty()

    override val screen: Screen by lazy {
        Screen.create(context).apply {
            addChild(videoContainer)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    private var outputs = mutableListOf<MediaOutput>()

    @Volatile
    private var keepAlive = true
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
        doAudio()
    }

    /**
     * Attaches a audio source.
     */
    suspend fun attachAudio(
        track: Int,
        audio: AudioSource?,
    ): Result<Unit> {
        if (audio != null) {
            audioSources.remove(track)?.close()
            audioSources[track] = audio
            return audio.open(this)
        }
        audioSources.remove(track)?.close()
        return Result.success(Unit)
    }

    /**
     * Attaches a video source.
     */
    suspend fun attachVideo(
        track: Int,
        video: VideoSource?,
    ): Result<Unit> {
        if (video != null) {
            videoSources.remove(track)?.let {
                videoContainer.removeChild(it.video)
                it.close()
            }
            videoSources[track] = video
            return video.open(this).onSuccess {
                videoContainer.addChild(video.video)
            }
        }
        videoSources.remove(track)?.let {
            videoContainer.removeChild(it.video)
            it.close()
        }
        return Result.success(Unit)
    }

    /**
     * Sets a video effect.
     */
    fun setVideoEffect(
        track: Int,
        videoEffect: VideoEffect,
    ) {
        videoSources[track]?.video?.videoEffect = videoEffect
    }

    override fun registerOutput(output: MediaOutput) {
        if (!outputs.contains(output)) {
            output.dataSource = WeakReference(this)
            outputs.add(output)
        }
    }

    override fun unregisterOutput(output: MediaOutput) {
        if (outputs.contains(output)) {
            outputs.remove(output)
            output.dataSource = null
        }
    }

    /**
     * Disposes the stream of memory management.
     */
    fun dispose() {
        keepAlive = false
        orientationEventListener.disable()
        launch {
            audioSources.values.forEach { it.close() }
            videoSources.values.forEach {
                videoContainer.removeChild(it.video)
                it.close()
            }
        }
        audioSources.clear()
        videoSources.clear()
        screen.dispose()
    }

    private fun doAudio() =
        launch {
            while (keepAlive) {
                if (audioSources.isEmpty()) {
                    delay(1000)
                }
                audioSources.forEach { audio ->
                    val buffer = audio.value.read(audio.key)
                    outputs.forEach { output ->
                        output.append(buffer)
                    }
                }
            }
        }

    private companion object {
        private val TAG = MediaMixer::class.java.simpleName
    }
}
