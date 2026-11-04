package com.haishinkit.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import kotlin.properties.Delegates

/**
 * Encodes and decodes audio using Android media codecs.
 */
class AudioCodec : Codec() {
    /**
     * Audio encoder settings. Changes are forwarded to the associated codec when one is supplied.
     */
    @Suppress("UNUSED")
    data class Setting(
        private var codec: AudioCodec? = null,
    ) : Codec.Setting(codec) {
        /**
         * The number of output audio channels; defaults to 1 (mono).
         */
        var channelCount: Int by Delegates.observable(DEFAULT_CHANNEL_COUNT) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.channelCount = newValue
            }
        }

        /**
         * The target audio bit rate, in bits per second; defaults to 64,000.
         */
        var bitRate: Int by Delegates.observable(DEFAULT_BIT_RATE) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.bitRate = newValue
            }
        }

        /**
         * The audio sample rate, in hertz; defaults to 44,100.
         */
        var sampleRate: Int by Delegates.observable(DEFAULT_SAMPLE_RATE) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.sampleRate = newValue
            }
        }
    }

    var sampleRate = DEFAULT_SAMPLE_RATE
        set(value) {
            field = value
            buffer.sampleRate = value
        }
    var channelCount = DEFAULT_CHANNEL_COUNT
        set(value) {
            field = value
            buffer.channelCount = value
        }
    var bitRate = DEFAULT_BIT_RATE
    var aacProfile = DEFAULT_AAC_PROFILE
    override var inputMimeType = MediaFormat.MIMETYPE_AUDIO_RAW
    override var outputMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
    private var buffer = AudioCodecBuffer()
    private val inputIndices = java.util.ArrayDeque<Int>()

    @Synchronized
    fun append(byteBuffer: ByteBuffer) {
        if (!isRunning.get()) return
        buffer.append(byteBuffer)
        drainInputs()
    }

    override fun onInputBufferAvailable(
        codec: MediaCodec,
        index: Int,
    ) {
        if (mode != MODE_ENCODE) {
            super.onInputBufferAvailable(codec, index)
            return
        }
        synchronized(this) {
            if (!isRunning.get() || codec !== this.codec) return
            inputIndices.addLast(index)
            drainInputs()
        }
    }

    override fun createMediaFormat(mime: String): MediaFormat =
        MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
            if (mode == MODE_ENCODE) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            } else {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_KEY_MAX_INPUT_SIZE)
            }
        }

    @Synchronized
    override fun dispose() {
        inputIndices.clear()
        buffer.clear()
        super.dispose()
    }

    private fun drainInputs() {
        if (mode != MODE_ENCODE) return
        try {
            val encoder = codec ?: return
            while (inputIndices.isNotEmpty()) {
                val index = inputIndices.peekFirst()
                val input = encoder.getInputBuffer(index) ?: return
                input.clear()
                val count = buffer.render(input) ?: return
                inputIndices.removeFirst()
                encoder.queueInputBuffer(index, 0, count, buffer.presentationTimestamp, 0)
            }
        } catch (cause: IllegalStateException) {
            Log.w(TAG, cause)
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 44100
        const val DEFAULT_CHANNEL_COUNT: Int = 1
        const val DEFAULT_BIT_RATE: Int = 64000
        const val DEFAULT_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC
        const val DEFAULT_KEY_MAX_INPUT_SIZE = 1024 * 2

        private val TAG = AudioCodec::class.java.simpleName
    }
}
