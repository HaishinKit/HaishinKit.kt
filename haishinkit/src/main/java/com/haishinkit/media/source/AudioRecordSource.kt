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
import com.haishinkit.BuildConfig
import com.haishinkit.codec.AudioCodec
import com.haishinkit.media.MediaMixer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * An audio source that captures a microphone by the AudioRecord api.
 */
@Suppress("MemberVisibilityCanBePrivate")
class AudioRecordSource(
    private val context: Context,
) : CoroutineScope,
    AudioSource {
    var channel = DEFAULT_CHANNEL
    var audioSource = DEFAULT_AUDIO_SOURCE
    var sampleRate = DEFAULT_SAMPLE_RATE
    override var isMuted = false
    override var mixer: MediaMixer? = null
    override val isRunning = AtomicBoolean(false)
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

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

    private var codecs = mutableListOf<AudioCodec>()
    private var encoding = DEFAULT_ENCODING
    private var sampleCount = DEFAULT_SAMPLE_COUNT
    private var noSignalBuffer = ByteBuffer.allocateDirect(0)
    private var byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(sampleCount * 2)

    @Volatile
    private var keepAlive = false

    override fun startRunning() {
        if (isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "startRunning()")
        }
        doAudio()
        isRunning.set(true)
    }

    override fun stopRunning() {
        if (!isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stopRunning()")
        }
        keepAlive = false
        isRunning.set(false)
    }

    override fun registerAudioCodec(codec: AudioCodec) {
        if (codecs.contains(codec)) return
        codec.sampleRate = sampleRate
        codecs.add(codec)
    }

    override fun unregisterAudioCodec(codec: AudioCodec) {
        if (!codecs.contains(codec)) return
        codecs.remove(codec)
    }

    private fun doAudio() =
        launch {
            keepAlive = true
            try {
                audioRecord?.startRecording()
            } catch (e: IllegalStateException) {
                Log.w(TAG, e)
            }
            while (keepAlive) {
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
                if (0 <= result) {
                    codecs.forEach {
                        it.append(byteBuffer)
                    }
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, error(result))
                    }
                }
            }
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: java.lang.IllegalStateException) {
                Log.w(TAG, e)
            }
            audioRecord = null
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
                } catch (e: Exception) {
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
