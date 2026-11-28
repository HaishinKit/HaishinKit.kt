package com.haishinkit.media.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import com.haishinkit.media.audio.PcmAudioFormat
import java.nio.ByteBuffer

/**
 * Captures media/game playback allowed by other applications, using an authorized projection.
 * The owner retains the projection; closing this audio source does not stop screen capture.
 * This application's playback is excluded to prevent feedback or double-mixing asset audio.
 */
@RequiresApi(29)
class MediaProjectionAudioSource(
    private val context: Context,
    private val projection: MediaProjection,
    override val audioFormat: PcmAudioFormat = PcmAudioFormat(48_000, 2),
    /** Capture only this application's UID; null captures other eligible applications. */
    private val capturedUid: Int? = null,
) : PcmAudioSource {
    @Volatile override var isMuted = false

    @Volatile private var recorder: AudioRecord? = null
    private var registered = false
    private var projectionStopped = false
    private val buffer = ByteBuffer.allocateDirect(1024 * audioFormat.channels * 2)
    private val callback =
        object : MediaProjection.Callback() {
            override fun onStop() =
                synchronized(this@MediaProjectionAudioSource) {
                    projectionStopped = true
                    runCatching { releaseRecorder() }
                    Unit
                }
        }

    init {
        require(audioFormat.encoding == AudioFormat.ENCODING_PCM_16BIT)
        require(capturedUid == null || capturedUid >= 0)
    }

    override suspend fun open(mixer: MediaMixer): Result<Unit> =
        runCatching {
            synchronized(this) {
                check(!projectionStopped) { "MediaProjection has stopped" }
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Recording permission is required" }
                if (recorder != null) return@synchronized
                try {
                    projection.registerCallback(callback, Handler(Looper.getMainLooper()))
                    registered = true
                    val builder =
                        AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    if (capturedUid == null) builder.excludeUid(Process.myUid()) else builder.addMatchingUid(capturedUid)
                    val configuration = builder.build()
                    val mask = if (audioFormat.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                    recorder =
                        AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(configuration)
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(audioFormat.sampleRate).setChannelMask(mask).setEncoding(audioFormat.encoding).build())
                            .setBufferSizeInBytes(maxOf(buffer.capacity(), AudioRecord.getMinBufferSize(audioFormat.sampleRate, mask, audioFormat.encoding)))
                            .build()
                    recorder!!.startRecording()
                } catch (cause: Exception) {
                    releaseRecorder()
                    if (registered) projection.unregisterCallback(callback)
                    registered = false
                    throw cause
                }
            }
        }

    override suspend fun close(): Result<Unit> =
        runCatching {
            synchronized(this) {
                try {
                    releaseRecorder()
                } finally {
                    if (registered) projection.unregisterCallback(callback)
                    registered = false
                }
            }
        }

    override fun read(track: Int): MediaBuffer {
        buffer.clear()
        val count =
            try {
                recorder?.read(buffer, buffer.capacity()) ?: -1
            } catch (_: IllegalStateException) {
                -1
            }
        buffer.limit(count.coerceAtLeast(0))
        if (isMuted) for (i in 0 until buffer.limit()) buffer.put(i, 0.toByte())
        if (count <= 0) SystemClock.sleep(10)
        return MediaBuffer(MediaType.AUDIO, track, buffer)
    }

    private fun releaseRecorder() {
        val previous = recorder ?: return
        recorder = null
        try {
            if (previous.recordingState == AudioRecord.RECORDSTATE_RECORDING) previous.stop()
        } finally {
            previous.release()
        }
    }
}
