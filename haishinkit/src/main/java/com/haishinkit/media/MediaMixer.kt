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
import com.haishinkit.screen.VideoScreenObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Routes captured audio and composited video to registered outputs.
 *
 * Attach capture sources to numbered tracks, register streams or recorders with [registerOutput],
 * and call [startRunning] to start capture. Call [stopRunning] to stop capture and [dispose]
 * when the mixer is no longer needed.
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
     * Whether the torch is enabled for attached camera sources; defaults to `false`.
     */
    var isToucheEnabled: Boolean = false
        set(value) {
            videoSources.values.forEach {
                val source = it as? Camera2Source ?: return@forEach
                source.isTorchEnabled = value
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
        ScreenObjectContainer().apply {
            addChild(VideoScreenObject())
        }
    }
    private val orientationEventListener: OrientationEventListener by lazy {
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                val windowManager =
                    context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.defaultDisplay?.orientation?.let { orientation ->
                    screen.findByClass(VideoScreenObject::class.java).forEach {
                        it.deviceOrientation = orientation
                    }
                }
            }
        }
    }

    /**
     * Replaces the audio source on a track, closing the previous source first.
     *
     * The new source is opened immediately, even if the mixer has not started.
     *
     * @param track The audio track identifier.
     * @param audio The source to attach, or `null` to detach the current source.
     * @return The result of opening the new source, or success when detaching.
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
     * Replaces the video source on a track, closing the previous source first.
     *
     * If the mixer is running, the new source is opened and attached to the screen immediately.
     * Otherwise, it is opened when [startRunning] is called.
     *
     * @param track The video track identifier, matched by `VideoScreenObject.track`.
     * @param video The source to attach, or `null` to detach the current source.
     * @return The result of opening the source, or success when opening is deferred or the source is detached.
     */
    suspend fun attachVideo(
        track: Int,
        video: VideoSource?,
    ): Result<Unit> {
        if (video != null) {
            attachVideo(track, null)
            videoSources[track] = video
            if (video is Camera2Source) {
                video.isTorchEnabled = isToucheEnabled
            }
            return if (isRunning.get()) {
                video.open(this).onSuccess {
                    screen.attachVideo(track, video)
                }
            } else {
                Result.success(Unit)
            }
        }
        videoSources.remove(track)?.let {
            it.surface = null
            it.close()
        }
        screen.attachVideo(track, null)
        return Result.success(Unit)
    }

    /**
     * Applies an effect to the video screen objects currently assigned to [track].
     *
     * @param track The video track identifier.
     * @param videoEffect The effect to apply.
     */
    fun setVideoEffect(
        track: Int,
        videoEffect: VideoEffect,
    ) {
        screen.findByClass(VideoScreenObject::class.java).forEach {
            if (it.track == track) {
                it.videoEffect = videoEffect
            }
        }
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
     * Starts capture and schedules attached sources to open asynchronously. Does nothing if already running.
     */
    override fun startRunning() {
        if (isRunning.get()) {
            return
        }
        orientationEventListener.enable()
        launch {
            videoSources.forEach {
                val track = it.key
                val source = it.value
                source.open(this@MediaMixer).onSuccess {
                    screen.attachVideo(track, source)
                }
            }
            audioSources.values.forEach { it.open(this@MediaMixer) }
        }
        keepAlive = true
        startAudioCapturing()
        isRunning.set(true)
    }

    /**
     * Stops capture and schedules attached sources to close asynchronously. Does nothing if already stopped.
     */
    override fun stopRunning() {
        if (!isRunning.get()) {
            return
        }
        keepAlive = false
        orientationEventListener.disable()
        launch {
            videoSources.forEach {
                val track = it.key
                val source = it.value
                source.close()
                screen.attachVideo(track, null)
            }
            audioSources.values.forEach { it.close() }
        }
        isRunning.set(false)
    }

    /**
     * Stops capture, removes screen children, and clears the attached source lists.
     */
    fun dispose() {
        stopRunning()
        screen.dispose()
        audioSources.clear()
        videoSources.clear()
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
