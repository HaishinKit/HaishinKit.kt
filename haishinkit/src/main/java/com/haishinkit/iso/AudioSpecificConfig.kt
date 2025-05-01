package com.haishinkit.iso

import android.media.MediaCodecInfo
import android.util.Log
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.CodecOption
import com.haishinkit.util.toPositiveInt
import java.nio.ByteBuffer

/**
 * The Audio Specific Config is the global header for MPEG-4 Audio
 * @see http://wiki.multimedia.cx/index.php?title=MPEG-4_Audio.Audio_Specific_Config
 * @see http://wiki.multimedia.cx/?title=Understanding_AAC
 */
internal data class AudioSpecificConfig(
    val type: AudioObjectType = AudioObjectType.UNKNOWN,
    val frequency: SamplingFrequency = SamplingFrequency.HZ44100,
    val channel: ChannelConfiguration = ChannelConfiguration.FRONT_OF_CENTER,
) {
    enum class AudioObjectType(
        val rawValue: Byte,
    ) {
        UNKNOWN(0x00),
        AAC_MAIN(0x01),
        AAC_LC(0x02),
        AAC_SSL(0x03),
        AAC_LTP(0x04),
        AAC_SBR(0x05),
        AAC_SCALABLE(0x06),
        TWINQVQ(0x07),
        CELP(0x08),
        HXVC(0x09),
    }

    enum class SamplingFrequency(
        val rawValue: Byte,
        val int: Int,
    ) {
        HZ96000(0x00, 96000),
        HZ88200(0x01, 88200),
        HZ64000(0x02, 64000),
        HZ48000(0x03, 48000),
        HZ44100(0x04, 44100),
        HZ32000(0x05, 32000),
        HZ24000(0x06, 24000),
        HZ22050(0x07, 22050),
        HZ16000(0x08, 16000),
        HZ12000(0x09, 12000),
        HZ11025(0x0A, 11025),
        HZ8000(0x0B, 8000),
        HZ7350(0x0C, 7350),
    }

    enum class ChannelConfiguration(
        val rawValue: Byte,
    ) {
        DEFINE_IN_AOT_SPECIFIC_CONFIG(0x00),
        FRONT_OF_CENTER(0x01),
        FRONT_LEFT_AND_FRONT_RIGHT(0x02),
        FRONT_CENTER_AND_FRONT_LEFT_AND_FRONT_RIGHT(0x03),
        FRONT_CENTER_AND_FRONT_LEFT_AND_FRONT_RIGHT_AND_BACK_CENTER(0x04),
        FRONT_CENTER_AND_FRONT_LEFT_AND_FRONT_RIGHT_AND_BACK_LEFT_AND_BACK_RIGHT(0x05),
        FRONT_CENTER_AND_FRONT_LEFT_AND_FRONT_RIGHT_AND_BACK_LEFT_AND_BACK_RIGHT_LFE(0x06),
        FRONT_CENTER_AND_FRONT_LEFT_AND_FRONT_RIGHT_AND_SIDE_LEFT_AND_RIGHT_AND_BACK_RIGHT_LFE(0x07),
        UNKNOWN(Byte.MAX_VALUE),
    }

    fun encode(buffer: ByteBuffer): AudioSpecificConfig {
        val frequency = this.frequency.rawValue.toPositiveInt()
        buffer.put(((type.rawValue.toInt() shl 3) or (frequency shr 1)).toByte())
        buffer.put(
            ((frequency shl 7).toByte().toInt() or (channel.rawValue.toInt() shl 3)).toByte(),
        )
        return this
    }

    internal fun apply(codec: AudioCodec) {
        codec.sampleRate = frequency.int
        codec.channelCount = channel.rawValue.toInt()
        when (type) {
            AudioObjectType.AAC_MAIN -> {
                codec.aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectMain
            }

            AudioObjectType.AAC_LC -> {
                codec.aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC
            }

            else -> {
            }
        }
        val buffer = ByteBuffer.allocate(2)
        encode(buffer)
        buffer.flip()
        codec.options = listOf(CodecOption(CSD0, buffer))
        Log.i(TAG, "apply value=$this for an audioCodec")
    }

    companion object {
        private const val CSD0 = "csd-0"
        private var TAG = AudioSpecificConfig::class.java.simpleName

        fun decode(buffer: ByteBuffer): AudioSpecificConfig {
            val first = buffer.get().toPositiveInt()
            val second = buffer.get().toPositiveInt()
            return AudioSpecificConfig(
                type = AudioObjectType.values().first { n -> n.rawValue.toInt() == first shr 3 },
                frequency =
                    SamplingFrequency
                        .values()
                        .first { n -> n.rawValue.toInt() == (first and 7 shl 1 or (second and 0xFF shr 7)) },
                channel =
                    ChannelConfiguration
                        .values()
                        .first { n -> n.rawValue.toInt() == second and 120 shr 3 },
            )
        }
    }
}
