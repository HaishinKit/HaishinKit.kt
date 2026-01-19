package com.haishinkit.media

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.lang.Running
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.Camera2Source
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObjectContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Mixing audio and video for streaming.
 */
@Suppress("UNUSED")
class MediaMixer(
    context: Context,
    override val isRunning: AtomicBoolean = AtomicBoolean(false),
) : MediaOutputDataSource,
    CoroutineScope,
    DefaultLifecycleObserver,
    Running {
    /**
     * Specifies the device torch indicating whether the turn on(TRUE) or not(FALSE).
     */
    var isToucheEnabled: Boolean = false
        set(value) {
            videoSources.values.forEach {
                val source = it as? Camera2Source ?: return@forEach
                source.isTouchEnabled = value
            }
            field = value
        }

    /**
     * Specifies the audio mixer settings.
     */
    var audioMixerSettings = AudioMixerSettings()
        set(value) {
            if (field.isMuted != value.isMuted) {
                audioSources.values.forEach {
                    it.isMuted = value.isMuted
                }
            }
            field = value
        }

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

    /**
     * Attaches a audio source.
     */
    suspend fun attachAudio(
        track: Int,
        audio: AudioSource?,
    ): Result<Unit> {
        if (audio != null) {
            attachAudio(track, null)
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
            attachVideo(track, null)
            videoSources[track] = video
            if (video is Camera2Source) {
                video.isTouchEnabled = isToucheEnabled
            }
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

    override fun startRunning() {
        if (isRunning.get()) {
            return
        }
        orientationEventListener.enable()
        startAudioCapturing()
        isRunning.set(true)
    }

    override fun stopRunning() {
        if (!isRunning.get()) {
            return
        }
        keepAlive = false
        orientationEventListener.disable()
        isRunning.set(false)
    }

    /**
     * Disposes the stream of memory management.
     */
    fun dispose() {
        stopRunning()
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

    private fun startAudioCapturing() =
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
