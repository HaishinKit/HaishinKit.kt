package com.haishinkit.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
        /** Audio encoder MIME type. Set AAC or Opus before publishing; the transport must support it. */
        var mimeType: String = MediaFormat.MIMETYPE_AUDIO_AAC
            set(value) {
                require(value == MediaFormat.MIMETYPE_AUDIO_AAC || value == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                    "Audio publishing supports AAC or Opus"
                }
                check(codec?.isRunning?.get() != true) { "Stop the audio codec before changing its MIME type" }
                field = value
                codec?.outputMimeType = value
            }

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

    override fun createMediaCodec(): MediaCodec {
        if (mode == MODE_ENCODE && outputMimeType == MediaFormat.MIMETYPE_AUDIO_OPUS) {
            check(
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(outputMimeType, ignoreCase = true) }
                },
            ) { "Opus encoder is unavailable on this device; Android 10+ provides a platform Opus encoder" }
        }
        return super.createMediaCodec()
    }

    override fun createMediaFormat(mime: String): MediaFormat =
        MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
            if (mode == MODE_ENCODE) {
                if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                    require(sampleRate in setOf(8000, 12000, 16000, 24000, 48000) && channelCount in 1..2) {
                        "Opus requires mono/stereo PCM at 8/12/16/24/48 kHz"
                    }
                }
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                }
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            } else {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) 61_440 else DEFAULT_KEY_MAX_INPUT_SIZE)
            }
        }

    override fun onDisposed() {
        synchronized(this) {
            inputIndices.clear()
            buffer.clear()
        }
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
            Log.w(TAG, "Unable to queue PCM audio for encoding: mime=$outputMimeType, pendingBuffers=${inputIndices.size}", cause)
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
