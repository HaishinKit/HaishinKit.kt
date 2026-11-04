package com.haishinkit.media

import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import com.haishinkit.BuildConfig
import com.haishinkit.lang.Running
import com.haishinkit.metrics.FrameTracker
import com.haishinkit.stream.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Schedules decoded audio and video for synchronized playback.
 *
 * @property stream The stream whose codec output buffers are queued and released.
 */
class MediaLink(
    val stream: Stream,
) : Running,
    CoroutineScope,
    Choreographer.FrameCallback {
    /**
     * Whether the audio playback track is paused. Setting this has no effect if no track exists.
     */
    var paused: Boolean
        get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED
        set(value) {
            when (audioTrack?.playState) {
                AudioTrack.PLAYSTATE_STOPPED -> {
                }

                AudioTrack.PLAYSTATE_PLAYING -> {
                    if (value) {
                        audioTrack?.pause()
                    }
                }

                AudioTrack.PLAYSTATE_PAUSED -> {
                    if (!value) {
                        audioTrack?.play()
                    }
                }
            }
        }

    override val isRunning = AtomicBoolean(false)
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    /**
     * The playback audio track. Replacing it releases the existing track;
     * setting it to `null` switches to clock synchronization.
     * Track replacement preserves elapsed playback time.
     */
    var audioTrack: AudioTrack? = null
        get() = synchronized(audioTrackGuard) { field }
        set(value) {
            synchronized(audioTrackGuard) {
                if (field === value) return
                val previous = field
                playbackClock.replaceAudio(previous?.let { (it.playbackHeadPosition.toLong() and 0xffffffffL) * 1_000_000 / it.sampleRate })
                if (value == null) {
                    playbackClock.useClock()
                    videoTimestamp.clear()
                }
                field?.stop()
                field?.flush()
                field?.release()
                syncMode = if (value == null) SYNC_MODE_CLOCK else SYNC_MODE_AUDIO
                field = value
            }
        }

    private val audioTrackGuard = Any()
    private var syncMode = SYNC_MODE_CLOCK
    private var hasKeyframe = false
    private val videoBuffers = LinkedBlockingDeque<MediaBuffer>()
    private val audioBuffers = Channel<MediaBuffer>(Channel.UNLIMITED, onUndeliveredElement = { stream.releaseOutputBuffer(it, false) })
    private var choreographer: Choreographer? = null
        set(value) {
            field?.removeFrameCallback(this)
            field = value
        }
    private var videoTimestamp = MediaTimestamp(1000L)
    private var videoTimestampZero: Long = -1
    private var handler: Handler? = null
        get() {
            if (field == null) {
                val thread =
                    HandlerThread(javaClass.name, android.os.Process.THREAD_PRIORITY_DISPLAY)
                thread.start()
                field = Handler(thread.looper)
            }
            return field
        }
        set(value) {
            field?.looper?.quitSafely()
            field = value
        }

    @Volatile
    private var keepAlive = true
    private var frameTracker: FrameTracker? = null
        get() {
            if (field == null && VERBOSE) {
                field = FrameTracker()
            }
            return field
        }
    private val playbackClock = MediaPlaybackClock()
    private var audioPlaybackJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    /**
     * Queues the audio data asynchronously for playback.
     */
    fun queueAudio(buffer: MediaBuffer) {
        if (!isRunning.get()) {
            stream.releaseOutputBuffer(buffer, false)
            return
        }
        audioBuffers.trySend(buffer)
        if (!stream.hasVideo) {
            val track = audioTrack ?: return
            if (track.playbackHeadPosition <= 0) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
            }
        }
    }

    /**
     * Queues the video data asynchronously for playback.
     */
    fun queueVideo(buffer: MediaBuffer) {
        if (!isRunning.get()) {
            stream.releaseOutputBuffer(buffer, false)
            return
        }
        if (videoTimestampZero == -1L) {
            videoTimestampZero = buffer.timestamp
        }
        videoBuffers.add(buffer)
        if (choreographer == null) {
            handler?.post {
                choreographer = Choreographer.getInstance()
                choreographer?.postFrameCallback(this)
            }
        }
    }

    @Synchronized
    override fun startRunning() {
        if (isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "startRunning()")
        }
        keepAlive = true
        frameTracker?.clear()

        // audio setup
        audioTrack = null
        while (true) stream.releaseOutputBuffer(audioBuffers.tryReceive().getOrNull() ?: break, false)
        synchronized(audioTrackGuard) {
            playbackClock.reset()
            videoTimestamp.clear()
        }
        audioPlaybackJob =
            launch(coroutineContext) {
                doAudio()
            }

        // video setup
        hasKeyframe = false
        videoTimestampZero = -1
        videoBuffers.clear()

        isRunning.set(true)
    }

    @Synchronized
    override fun stopRunning() {
        if (!isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stopRunning()")
        }
        handler = null
        choreographer = null
        keepAlive = false
        audioPlaybackJob = null
        isRunning.set(false)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning.get()) return
        if (keepAlive) {
            choreographer?.postFrameCallback(this)
        }
        val duration =
            synchronized(audioTrackGuard) {
                val track = audioTrack
                if (syncMode == SYNC_MODE_AUDIO && track != null) {
                    if (track.playbackHeadPosition <= 0) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        return
                    }
                    playbackClock.audioTime((track.playbackHeadPosition.toLong() and 0xffffffffL) * 1_000_000 / track.sampleRate)
                } else {
                    videoTimestamp.nanoTime = frameTimeNanos
                    playbackClock.clockTime(videoTimestamp.duration)
                }
            }
        try {
            val it = videoBuffers.iterator()
            var frameCount = 0
            while (it.hasNext()) {
                val buffer = it.next()
                if (!hasKeyframe) {
                    hasKeyframe = buffer.sync
                }
                if (buffer.timestamp - videoTimestampZero <= duration) {
                    if (frameCount == 0 && hasKeyframe) {
                        if (VERBOSE) {
                            frameTracker?.track(FrameTracker.TYPE_VIDEO, SystemClock.uptimeMillis())
                        }
                        stream.releaseOutputBuffer(buffer, true)
                    } else {
                        stream.releaseOutputBuffer(buffer, false)
                    }
                    frameCount++
                    it.remove()
                } else {
                    if (VERBOSE && 2 < frameCount) {
                        Log.d(TAG, "droppedFrame: ${frameCount - 1}")
                    }
                    break
                }
            }
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "", e)
            }
        }
    }

    private suspend fun doAudio() {
        while (currentCoroutineContext().isActive && keepAlive) {
            val buffer = audioBuffers.receive()
            try {
                val payload = buffer.payload ?: continue
                if (VERBOSE) frameTracker?.track(FrameTracker.TYPE_AUDIO, SystemClock.uptimeMillis())
                while (payload.hasRemaining() && currentCoroutineContext().isActive && keepAlive) {
                    var written = 0
                    val valid =
                        buffer.codecOutput?.process {
                            synchronized(audioTrackGuard) {
                                written = audioTrack?.write(payload, payload.remaining(), AudioTrack.WRITE_NON_BLOCKING) ?: 0
                            }
                        } == true
                    if (!valid || written < 0) break
                    if (written == 0) yield()
                }
            } catch (e: IllegalStateException) {
                if (BuildConfig.DEBUG) Log.w(TAG, "", e)
            } finally {
                stream.releaseOutputBuffer(buffer, false)
            }
        }
    }

    private companion object {
        private const val SYNC_MODE_AUDIO = 0
        private const val SYNC_MODE_VSYNC = 1
        private const val SYNC_MODE_CLOCK = 2

        private const val VERBOSE = false
        private val TAG = MediaLink::class.java.simpleName
    }
}
