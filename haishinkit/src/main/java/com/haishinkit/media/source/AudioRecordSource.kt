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
import com.haishinkit.media.MediaLink
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import java.nio.ByteBuffer

/**
 * An audio source that captures a microphone by the AudioRecord api.
 */
@Suppress("MemberVisibilityCanBePrivate")
class AudioRecordSource(
    private val context: Context,
) : AudioSource {
    override var isMuted = false
    var channel = DEFAULT_CHANNEL
    var audioSource = DEFAULT_AUDIO_SOURCE
    var sampleRate = DEFAULT_SAMPLE_RATE
    var minBufferSize = -1
        get() {
            if (field == -1) {
                field = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            }
            return field
        }
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

    override fun read(track: Int): MediaLink.Buffer {
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
        return MediaLink.Buffer(
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

        private fun error(result: Int): String =
            when (result) {
                AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                AudioRecord.ERROR -> "ERROR"
                else -> "ERROR($result)"
            }

        private val TAG = AudioRecordSource::class.java.simpleName
    }
}
