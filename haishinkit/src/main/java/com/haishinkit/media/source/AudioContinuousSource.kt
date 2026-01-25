package com.haishinkit.media.source

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.haishinkit.device.MicrophoneDeviceManager
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

@RequiresApi(Build.VERSION_CODES.M)
class AudioContinuousSource(
    private val context: Context,
) : AudioSource, CoroutineScope {
    override var isMuted = false
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
    private var microphoneDeviceManager: MicrophoneDeviceManager? = null
        set(value) {
            field?.release()
            field = value
        }

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        val microphoneDeviceManager = MicrophoneDeviceManager(context)
        launch {
            microphoneDeviceManager.headsetConnected.collect {
                Log.d("TAG", it.toString())
            }
        }
        this.microphoneDeviceManager = microphoneDeviceManager
        return Result.success(Unit)
    }

    override suspend fun close(): Result<Unit> {
        this.microphoneDeviceManager = null
        cancel()
        return Result.success(Unit)
    }

    override fun read(track: Int): MediaBuffer {
        return MediaBuffer(
            type = MediaType.AUDIO,
            index = track,
            payload = ByteBuffer.allocate(0),
            timestamp = 0,
            sync = true,
        )
    }

    private fun switchAudioSource() {
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
