package com.haishinkit.media.source

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import com.haishinkit.media.audio.PcmAudioFormat
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
    override val audioFormat: PcmAudioFormat
        get() =
            synchronized(recorderLock) {
                PcmAudioFormat(cachedAudioRecord?.sampleRate ?: sampleRate, cachedAudioRecord?.channelCount ?: Integer.bitCount(channel))
            }

    /**
     * The input channel mask, such as `AudioFormat.CHANNEL_IN_MONO`.
     */
    var channel = DEFAULT_CHANNEL

    /**
     * The Android audio capture source; defaults to `MediaRecorder.AudioSource.CAMCORDER`.
     */
    var audioSource = DEFAULT_AUDIO_SOURCE

    /**
     * The requested client PCM sample rate, in hertz. Configure before opening the recorder.
     * Android converts the hardware input to this rate; route changes do not change it.
     */
    var sampleRate = DEFAULT_SAMPLE_RATE

    /**
     * Prefer a connected Bluetooth headset microphone, falling back to system input on removal.
     * Configure before [open]. Requires `MODIFY_AUDIO_SETTINGS` permission.
     * This opts into voice-communication capture/routing, which can also route playback to
     * the headset. [sampleRate] and [channel] remain the PCM format delivered to the mixer;
     * Android converts the device's native format. Match these to the stream's audio settings.
     */
    var preferBluetoothMicrophone = false

    /**
     * The capture buffer size, in bytes; computed on first access unless explicitly assigned.
     */
    var minBufferSize = -1
        get() =
            synchronized(recorderLock) {
                if (field == -1) {
                    field = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
                }
                return field
            }

    /**
     * The lazily created recorder, or `null` if audio recording permission has not been granted.
     */
    val audioRecord: AudioRecord?
        get() =
            synchronized(recorderLock) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@synchronized null
                }
                if (cachedAudioRecord == null) {
                    cachedAudioRecord = createAudioRecord(if (preferBluetoothMicrophone) MediaRecorder.AudioSource.VOICE_COMMUNICATION else audioSource, sampleRate, channel, encoding, minBufferSize)
                }
                cachedAudioRecord
            }

    @Volatile
    private var cachedAudioRecord: AudioRecord? = null
    private val recorderLock = Any()
    private var bluetoothRoute: BluetoothMicrophoneRoute? = null
    private var recoveryFormat: CaptureFormat? = null
    private var nextRecoveryTime = 0L
    private var encoding = DEFAULT_ENCODING
    private var sampleCount = DEFAULT_SAMPLE_COUNT
    private var byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(sampleCount * 2)

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        return synchronized(recorderLock) {
            try {
                val recorder = audioRecord ?: return@synchronized Result.failure(SecurityException("Recording permission is required"))
                recoveryFormat = null
                nextRecoveryTime = 0
                if (preferBluetoothMicrophone && bluetoothRoute == null) {
                    bluetoothRoute = BluetoothMicrophoneRoute(context).also { it.open() }
                }
                recorder.startRecording()
                Result.success(Unit)
            } catch (e: RuntimeException) {
                runCatching { releaseRecorder() }
                runCatching { closeRoute() }
                Result.failure(e)
            }
        }
    }

    override suspend fun close(): Result<Unit> {
        return synchronized(recorderLock) {
            recoveryFormat = null
            try {
                try {
                    releaseRecorder()
                } finally {
                    closeRoute()
                }
                Result.success(Unit)
            } catch (e: RuntimeException) {
                Log.w(TAG, e)
                Result.failure(e)
            }
        }
    }

    override fun read(track: Int): MediaBuffer {
        byteBuffer.clear()
        val recorder = cachedAudioRecord
        val result =
            try {
                recorder?.read(byteBuffer, byteBuffer.capacity()) ?: AudioRecord.ERROR_INVALID_OPERATION
            } catch (_: IllegalStateException) {
                AudioRecord.ERROR_INVALID_OPERATION
            }
        // Direct-buffer reads do not advance position. Do not forward stale PCM after
        // short reads or input changes, and keep the downstream encoder running.
        byteBuffer.limit(result.coerceAtLeast(0))
        if (isMuted && result > 0) {
            for (index in 0 until result) byteBuffer.put(index, 0.toByte())
        }
        if (result == AudioRecord.ERROR_DEAD_OBJECT || recorder == null) recoverRecorder(recorder)
        if (result <= 0) SystemClock.sleep(10) // Avoid spinning while the input is unavailable.
        return MediaBuffer(
            type = MediaType.AUDIO,
            index = track,
            payload = byteBuffer,
            timestamp = 0,
            sync = true,
        )
    }

    private fun releaseRecorder() {
        val recorder = cachedAudioRecord ?: return
        cachedAudioRecord = null
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } finally {
            recorder.release()
        }
    }

    private fun closeRoute() {
        val route = bluetoothRoute
        bluetoothRoute = null
        route?.close()
    }

    private fun recoverRecorder(failed: AudioRecord?) =
        synchronized(recorderLock) {
            if (cachedAudioRecord !== failed) return@synchronized
            if (failed != null) {
                // Preserve the client format, independently of the replacement microphone's native rate.
                recoveryFormat = CaptureFormat(failed.audioSource, failed.sampleRate, failed.channelConfiguration, failed.audioFormat)
            }
            val format = recoveryFormat ?: return@synchronized
            if (SystemClock.uptimeMillis() < nextRecoveryTime) return@synchronized
            nextRecoveryTime = SystemClock.uptimeMillis() + 500
            try {
                releaseRecorder()
                cachedAudioRecord = createAudioRecord(format.source, format.rate, format.channel, format.encoding, AudioRecord.getMinBufferSize(format.rate, format.channel, format.encoding))
                cachedAudioRecord?.startRecording()
                recoveryFormat = null
            } catch (cause: RuntimeException) {
                Log.w(TAG, "Unable to recreate audio recorder after input change", cause)
                runCatching { releaseRecorder() }
            }
        }

    private data class CaptureFormat(val source: Int, val rate: Int, val channel: Int, val encoding: Int)

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
