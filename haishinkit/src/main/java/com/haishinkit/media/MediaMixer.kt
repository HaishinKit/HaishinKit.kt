package com.haishinkit.media

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.lang.Running
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.Camera2Source
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.VideoScreenObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        Screen.create(context, dynamicRangeMode).apply {
            addChild(videoContainer)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    /** Color pipeline fixed for this mixer's lifetime. */
    val dynamicRangeMode: DynamicRangeMode get() = selectedDynamicRangeMode

    private var selectedDynamicRangeMode = DynamicRangeMode.SDR
    private val outputs = MediaOutputRegistry(this)

    @Volatile
    private var keepAlive = true
    private val isDisposed = AtomicBoolean(false)
    private val videoLock = Mutex()
    private val openVideoTracks = mutableSetOf<Int>()

    @Volatile
    private var videoSources = emptyMap<Int, VideoSource>()
    private var audioSources = mutableMapOf<Int, AudioSource>()
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val defaultVideo: VideoScreenObject by lazy {
        VideoScreenObject().apply {
            deviceOrientation = windowManager.defaultDisplay.rotation
        }
    }
    private val videoContainer: ScreenObjectContainer by lazy {
        ScreenObjectContainer().apply {
            addChild(defaultVideo)
        }
    }
    private val orientationEventListener: OrientationEventListener by lazy {
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                updateDeviceOrientation()
            }
        }
    }

    /** Creates a mixer with an immutable color pipeline. */
    constructor(context: Context, dynamicRangeMode: DynamicRangeMode) : this(context) {
        selectedDynamicRangeMode = dynamicRangeMode
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
    ): Result<Unit> = attachVideos(mapOf(track to video))

    /**
     * Replaces the specified tracks together. All new sources are opened before any is
     * attached to a surface, as required for concurrent Camera2 capture. If replacement fails,
     * the previous configuration is restored and its closed sources are reopened when running.
     */
    suspend fun attachVideos(videos: Map<Int, VideoSource?>): Result<Unit> =
        videoLock.withLock {
            check(!isDisposed.get()) { "MediaMixer is disposed." }
            val previous = videoSources
            val replacements = videos.filterValues { it != null }.mapValues { checkNotNull(it.value) }
            // Streams consult hasVideo before starting their codecs, including during camera open.
            videoSources = previous - videos.keys + replacements
            try {
                videos.keys.forEach { track ->
                    previous[track]?.let { closeVideo(track, it) }
                }
                if (isRunning.get()) openVideos(replacements).getOrThrow()
                // Only camera frames are sensor-relative; projection and decoded frames
                // already carry their source orientation and must not rotate again.
                defaultVideo.isRotatesWithContent = videoSources[0] is Camera2Source
                Result.success(Unit)
            } catch (cause: Exception) {
                videoSources = previous
                if (isRunning.get()) {
                    withContext(NonCancellable) {
                        runCatching {
                            openVideos(previous.filterKeys { it in videos && it !in openVideoTracks }).getOrThrow()
                        }.onFailure {
                            if (it !== cause) cause.addSuppressed(it)
                        }
                    }
                }
                if (cause is CancellationException) throw cause
                Result.failure(cause)
            }
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

    override fun registerOutput(output: MediaOutput) = outputs.register(output)

    override fun unregisterOutput(output: MediaOutput) = outputs.unregister(output)

    /**
     * Starts capture and schedules attached sources to open asynchronously. Does nothing if already running.
     */
    override fun startRunning() {
        if (isDisposed.get() || !isRunning.compareAndSet(false, true)) return
        updateDeviceOrientation()
        orientationEventListener.enable()
        keepAlive = true
        launch {
            videoLock.withLock {
                if (isRunning.get()) openVideos(videoSources.filterKeys { it !in openVideoTracks })
            }
            audioSources.values.forEach { it.open(this@MediaMixer) }
        }
        startAudioCapturing()
    }

    /**
     * Stops capture and schedules attached sources to close asynchronously. Does nothing if already stopped.
     */
    override fun stopRunning() {
        if (!isRunning.compareAndSet(true, false)) return
        keepAlive = false
        orientationEventListener.disable()
        launch {
            videoLock.withLock {
                if (!isRunning.get() && !isDisposed.get()) closeOpenVideos()
            }
            audioSources.values.forEach { it.close() }
        }
    }

    /**
     * Stops capture, removes screen children, and clears the attached source lists.
     */
    fun dispose() {
        if (!isDisposed.compareAndSet(false, true)) return
        isRunning.set(false)
        keepAlive = false
        orientationEventListener.disable()
        launch {
            videoLock.withLock {
                closeOpenVideos()
                videoSources = emptyMap()
                screen.dispose()
            }
            audioSources.values.forEach { it.close() }
            audioSources.clear()
        }
    }

    private fun updateDeviceOrientation() {
        val rotation = windowManager.defaultDisplay.rotation
        defaultVideo.deviceOrientation = rotation
        screen.findByClass(VideoScreenObject::class.java).forEach {
            it.deviceOrientation = rotation
        }
    }

    private suspend fun openVideos(sources: Map<Int, VideoSource>): Result<Unit> {
        val attempted = mutableListOf<Pair<Int, VideoSource>>()
        try {
            sources.forEach { (track, source) ->
                attempted.add(track to source)
                source.open(this).getOrThrow()
            }
            sources.forEach { (track, source) ->
                screen.attachVideo(track, source)
                openVideoTracks.add(track)
            }
            return Result.success(Unit)
        } catch (cause: Exception) {
            withContext(NonCancellable) {
                attempted.forEach { (track, source) ->
                    runCatching { closeVideo(track, source) }.onFailure {
                        if (it !== cause) cause.addSuppressed(it)
                    }
                }
            }
            if (cause is CancellationException) throw cause
            return Result.failure(cause)
        }
    }

    private suspend fun closeOpenVideos() {
        openVideoTracks.toList().forEach { track ->
            videoSources[track]?.let { closeVideo(track, it) }
        }
    }

    private suspend fun closeVideo(
        track: Int,
        source: VideoSource,
    ) {
        openVideoTracks.remove(track)
        source.surface = null
        source.close()
        screen.attachVideo(track, null)
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
