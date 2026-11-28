package com.haishinkit.media.source

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import com.haishinkit.media.audio.PcmAudioFormat
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Captures PCM audio using [AudioRecord].
 *
 * Grant `RECORD_AUDIO` permission and configure capture settings before attaching this source
 * with `MediaMixer.attachAudio`. The default format is 16-bit mono PCM at 44,100 Hz.
 */
@Suppress("MemberVisibilityCanBePrivate")
class AudioRecordSource(
    private val context: Context,
) : PcmAudioSource {
    @Volatile override var isMuted = false
    override val audioFormat: PcmAudioFormat get() = PcmAudioFormat(cachedAudioRecord?.sampleRate ?: sampleRate, cachedAudioRecord?.channelCount ?: Integer.bitCount(channel))

    /**
     * The input channel mask, such as `AudioFormat.CHANNEL_IN_MONO`.
     */
    var channel = DEFAULT_CHANNEL

    /**
     * The Android audio capture source; defaults to `MediaRecorder.AudioSource.CAMCORDER`.
     */
    var audioSource = DEFAULT_AUDIO_SOURCE

    /**
     * The capture sample rate, in hertz.
     */
    var sampleRate = DEFAULT_SAMPLE_RATE

    /**
     * The capture buffer size, in bytes; computed on first access unless explicitly assigned.
     */
    var minBufferSize = -1
        get() {
            if (field == -1) {
                field = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            }
            return field
        }

    /**
     * The lazily created recorder, or `null` if audio recording permission has not been granted.
     */
    val audioRecord: AudioRecord?
        get() {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            if (cachedAudioRecord == null) {
                cachedAudioRecord = createAudioRecord(audioSource, sampleRate, channel, encoding, minBufferSize)
            }
            return cachedAudioRecord
        }

    /** Current capture session, without creating a recorder. Null while closed. */
    val audioSessionId: Int? get() = cachedAudioRecord?.audioSessionId

    @Volatile
    private var cachedAudioRecord: AudioRecord? = null
    private var encoding = DEFAULT_ENCODING
    private var sampleCount = DEFAULT_SAMPLE_COUNT
    private var byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(sampleCount * 2)

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        try {
            val recorder = audioRecord ?: return Result.failure(SecurityException("Recording permission is required"))
            recorder.startRecording()
        } catch (e: IllegalStateException) {
            return Result.failure(e)
        }
        return Result.success(Unit)
    }

    override suspend fun close(): Result<Unit> {
        val recorder = cachedAudioRecord ?: return Result.success(Unit)
        cachedAudioRecord = null
        try {
            try {
                recorder.stop()
            } finally {
                recorder.release()
            }
        } catch (e: java.lang.IllegalStateException) {
            Log.w(TAG, "Unable to stop or release microphone capture", e)
            return Result.failure(e)
        }
        return Result.success(Unit)
    }

    override fun read(track: Int): MediaBuffer = capture(track, false)

    /**
     * Reads PCM like [read], but throws [IOException] for negative AudioRecord error codes.
     * A valid zero-byte read still returns an empty buffer. Use this when an input pipeline
     * must retire a failed recorder rather than continue polling empty PCM.
     */
    @Throws(IOException::class)
    fun readChecked(track: Int): MediaBuffer = capture(track, true)

    private fun capture(
        track: Int,
        throwOnError: Boolean,
    ): MediaBuffer {
        byteBuffer.clear()
        val result = cachedAudioRecord?.read(byteBuffer, sampleCount * 2) ?: -1
        if (throwOnError && result < 0) {
            byteBuffer.limit(0)
            throw IOException("AudioRecord.read failed ($result)")
        }
        byteBuffer.limit(result.coerceAtLeast(0))
        if (isMuted) {
            for (index in 0 until result.coerceAtLeast(0)) byteBuffer.put(index, 0.toByte())
        }
        return MediaBuffer(
            type = MediaType.AUDIO,
            index = track,
            payload = byteBuffer,
            timestamp = 0,
            sync = true,
        )
    }

    companion object {
        const val DEFAULT_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val DEFAULT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.CAMCORDER
        const val DEFAULT_SAMPLE_COUNT = 1024

        private val TAG = AudioRecordSource::class.java.simpleName

        @SuppressLint("MissingPermission")
        private fun createAudioRecord(
            audioSource: Int,
            sampleRate: Int,
            channel: Int,
            encoding: Int,
            minBufferSize: Int,
        ): AudioRecord {
            return try {
                AudioRecord
                    .Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channel)
                            .build(),
                    ).setBufferSizeInBytes(minBufferSize)
                    .build()
            } catch (_: Exception) {
                AudioRecord(
                    audioSource,
                    sampleRate,
                    channel,
                    encoding,
                    minBufferSize,
                )
            }
        }
    }
}
