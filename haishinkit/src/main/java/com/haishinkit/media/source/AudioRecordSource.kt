package com.haishinkit.media.source

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
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
) : AudioSource {
    override var isMuted = false
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
    var audioRecord: AudioRecord? = null
        get() {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            if (field == null) {
                field = createAudioRecord(audioSource, sampleRate, channel, encoding, minBufferSize)
            }
            return field
        }
        private set

    private var encoding = DEFAULT_ENCODING
    private var sampleCount = DEFAULT_SAMPLE_COUNT
    private var noSignalBuffer = ByteBuffer.allocateDirect(0)
    private var byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(sampleCount * 2)

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        try {
            audioRecord?.startRecording()
        } catch (e: IllegalStateException) {
            return Result.failure(e)
        }
        return Result.success(Unit)
    }

    override suspend fun close(): Result<Unit> {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: java.lang.IllegalStateException) {
            Log.w(TAG, e)
            return Result.failure(e)
        }
        return Result.success(Unit)
    }

    override fun read(track: Int): MediaBuffer {
        byteBuffer.rewind()
        val result = audioRecord?.read(byteBuffer, sampleCount * 2) ?: -1
        if (isMuted) {
            if (noSignalBuffer.capacity() < result) {
                noSignalBuffer = ByteBuffer.allocateDirect(result)
            }
            noSignalBuffer.clear()
            byteBuffer.clear()
            byteBuffer.put(noSignalBuffer)
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

        @SuppressLint("MissingPermission")
        private fun createAudioRecord(
            audioSource: Int,
            sampleRate: Int,
            channel: Int,
            encoding: Int,
            minBufferSize: Int,
        ): AudioRecord {
            if (Build.VERSION_CODES.M <= Build.VERSION.SDK_INT) {
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
            } else {
                return AudioRecord(
                    audioSource,
                    sampleRate,
                    channel,
                    encoding,
                    minBufferSize,
                )
            }
        }

        private val TAG = AudioRecordSource::class.java.simpleName
    }
}
