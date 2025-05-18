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
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * The MediaLink class can be used to synchronously play audio and video streams.
 */
class MediaLink(
    val stream: Stream,
) : Running,
    CoroutineScope,
    Choreographer.FrameCallback {
    /**
     * Specifies the paused indicates the playback of a media pause(TRUE) or not(FALSE).
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

    var audioTrack: AudioTrack? = null
        set(value) {
            syncMode =
                if (value == null) {
                    field?.stop()
                    field?.flush()
                    field?.release()
                    SYNC_MODE_CLOCK
                } else {
                    SYNC_MODE_AUDIO
                }
            field = value
        }

    private var syncMode = SYNC_MODE_CLOCK
    private var hasKeyframe = false
    private val videoBuffers = LinkedBlockingDeque<MediaBuffer>()
    private val audioBuffers = LinkedBlockingDeque<MediaBuffer>()
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
    private var audioTimestampZero = 0L
    private var audioPlaybackJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    /**
     * Queues the audio data asynchronously for playback.
     */
    fun queueAudio(buffer: MediaBuffer) {
        if (!isRunning.get()) return
        audioBuffers.add(buffer)
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
        if (!isRunning.get()) return
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
        audioBuffers.clear()
        audioTimestampZero = 0
        audioPlaybackJob =
            launch(coroutineContext) {
                doAudio()
            }

        // video setup
        hasKeyframe = false
        videoTimestampZero = -1
        videoBuffers.clear()
        videoTimestamp.clear()

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
        if (keepAlive) {
            choreographer?.postFrameCallback(this)
        }
        val duration: Long
        if (syncMode == SYNC_MODE_AUDIO) {
            val track = audioTrack ?: return
            if (track.playbackHeadPosition <= 0) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                    audioTimestampZero = videoTimestamp.duration
                }
                return
            }
            duration =
                (track.playbackHeadPosition.toLong() * 1000 * 1000 / track.sampleRate) + audioTimestampZero
        } else {
            videoTimestamp.nanoTime = frameTimeNanos
            duration = videoTimestamp.duration
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

    private fun doAudio() {
        while (keepAlive) {
            try {
                val buffer = audioBuffers.take()
                val payload = buffer.payload ?: continue
                if (VERBOSE) {
                    frameTracker?.track(FrameTracker.TYPE_AUDIO, SystemClock.uptimeMillis())
                }
                while (payload.hasRemaining()) {
                    if (keepAlive) {
                        audioTrack?.write(
                            payload,
                            payload.remaining(),
                            AudioTrack.WRITE_NON_BLOCKING,
                        )
                    } else {
                        break
                    }
                }
                stream.releaseOutputBuffer(buffer, false)
            } catch (e: InterruptedException) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "", e)
                }
            } catch (e: IllegalStateException) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "", e)
                }
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
