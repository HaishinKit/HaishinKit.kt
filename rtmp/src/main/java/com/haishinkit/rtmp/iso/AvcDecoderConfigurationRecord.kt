package com.haishinkit.rtmp.iso

import android.media.MediaFormat
import android.util.Size
import com.haishinkit.codec.CodecOption
import com.haishinkit.util.toPositiveInt
import java.nio.ByteBuffer

internal data class AvcDecoderConfigurationRecord(
    val configurationVersion: Byte = 0x01,
    val avcProfileIndication: Byte = 0,
    val profileCompatibility: Byte = 0,
    val avcLevelIndication: Byte = 0,
    val lengthSizeMinusOneWithReserved: Byte = 0,
    val numOfSequenceParameterSetsWithReserved: Byte = 0,
    val sequenceParameterSets: List<ByteArray>? = null,
    val pictureParameterSets: List<ByteArray>? = null,
) : DecoderConfigurationRecord {
    val naluLength: Byte
        get() = ((lengthSizeMinusOneWithReserved.toInt() shr 6) + 1).toByte()
    override val mime: String
        get() = MediaFormat.MIMETYPE_VIDEO_AVC

    override val videoSize: Size?
        get() {
            val sequenceParameterSets = sequenceParameterSets ?: return null
            val byteArray = sequenceParameterSets.firstOrNull() ?: return null
            val byteBuffer = ByteBuffer.wrap(byteArray)
            val sequenceParameterSet = AvcSequenceParameterSet.decode(byteBuffer)
            return Size(sequenceParameterSet.videoWidth, sequenceParameterSet.videoHeight)
        }

    override val capacity: Int
        get() {
            var capacity = 5
            sequenceParameterSets?.let {
                for (sps in it) {
                    capacity += 3 + sps.size
                }
            }
            pictureParameterSets?.let {
                for (psp in it) {
                    capacity += 3 + psp.size
                }
            }
            return capacity
        }

    override fun encode(buffer: ByteBuffer): AvcDecoderConfigurationRecord {
        buffer.put(configurationVersion)
        buffer.put(avcProfileIndication)
        buffer.put(profileCompatibility)
        buffer.put(avcLevelIndication)
        buffer.put(lengthSizeMinusOneWithReserved)
        // SPS
        buffer.put(numOfSequenceParameterSetsWithReserved)
        sequenceParameterSets?.let {
            for (sps in it) {
                buffer.putShort(sps.size.toShort())
                buffer.put(sps)
            }
        }
        // PPS
        pictureParameterSets?.let {
            buffer.put(it.size.toByte())
            for (pps in it) {
                buffer.putShort(pps.size.toShort())
                buffer.put(pps)
            }
        }
        return this
    }

    override fun toCodecSpecificData(options: List<CodecOption>): List<CodecOption> {
        val mutableOptions = mutableListOf<CodecOption>()
        mutableOptions.addAll(options.filter { it.key != CSD0 && it.key != CSD1 })

        val spsBuffer = ByteBuffer.allocate(128)
        sequenceParameterSets?.let {
            for (sps in it) {
                spsBuffer.put(IsoTypeBufferUtils.START_CODE)
                spsBuffer.put(sps)
            }
        }
        spsBuffer.flip()

        val ppsBuffer = ByteBuffer.allocate(128)
        pictureParameterSets?.let {
            for (pps in it) {
                ppsBuffer.put(IsoTypeBufferUtils.START_CODE)
                ppsBuffer.put((pps))
            }
        }
        ppsBuffer.flip()

        mutableOptions.add(CodecOption(CSD0, spsBuffer))
        mutableOptions.add(CodecOption(CSD1, ppsBuffer))

        return mutableOptions
    }

    @Suppress("UNUSED")
    companion object : DecoderConfigurationRecordFactory {
        const val RESERVE_LENGTH_SIZE_MINUS_ONE = 0x3F
        const val RESERVE_NUM_OF_SEQUENCE_PARAMETER_SETS = 0xE0
        const val RESERVE_CHROME_FORMAT = 0xFC
        const val RESERVE_BIT_DEPTH_LUMA_MINUS8 = 0xF8
        const val RESERVE_BIT_DEPTH_CHROME_MINUS8 = 0xF8

        private const val CSD0 = "csd-0"
        private const val CSD1 = "csd-1"

        private var TAG = AvcDecoderConfigurationRecord::class.java.simpleName

        override fun create(mediaFormat: MediaFormat): AvcDecoderConfigurationRecord {
            // SPS => 0x00,0x00,0x00,0x01,0x67,0x42,0x00,0x29,0x8d,0x8d,0x40,0xa0,0xfd,0x00,0xf0,0x88,0x45,0x38
            val spsBuffer = mediaFormat.getByteBuffer(CSD0)
            // PPS => 0x00,0x00,0x00,0x01,0x68,0xca,0x43,0xc8
            val ppsBuffer = mediaFormat.getByteBuffer(CSD1)
            if (spsBuffer == null || ppsBuffer == null) {
                throw IllegalStateException()
            }
            return AvcDecoderConfigurationRecord(
                configurationVersion = 0x01,
                avcProfileIndication = spsBuffer[5],
                profileCompatibility = spsBuffer[6],
                avcLevelIndication = spsBuffer[7],
                lengthSizeMinusOneWithReserved = 0xFF.toByte(),
                numOfSequenceParameterSetsWithReserved = 0xE1.toByte(),
                sequenceParameterSets =
                    ArrayList<ByteArray>(1).apply {
                        val length = spsBuffer.remaining()
                        add(spsBuffer.array().slice(4 until length).toByteArray())
                    },
                pictureParameterSets =
                    ArrayList<ByteArray>(1).apply {
                        val length = ppsBuffer.remaining()
                        add(ppsBuffer.array().slice(4 until length).toByteArray())
                    },
            )
        }

        override fun decode(buffer: ByteBuffer): AvcDecoderConfigurationRecord {
            val configurationVersion = buffer.get()
            val avcProfileIndication = buffer.get()
            val profileCompatibility = buffer.get()
            val avcLevelIndication = buffer.get()
            val lengthSizeMinusOneWithReserved = buffer.get()
            val numOfSequenceParameterSetsWithReserved = buffer.get()

            val numOfSequenceParameterSets =
                numOfSequenceParameterSetsWithReserved.toPositiveInt() and RESERVE_NUM_OF_SEQUENCE_PARAMETER_SETS.inv()
            val sequenceParameterSets = mutableListOf<ByteArray>()
            for (i in 0 until numOfSequenceParameterSets) {
                val bytes = ByteArray(buffer.short.toInt())
                buffer.get(bytes)
                sequenceParameterSets.add(bytes)
            }

            val numPictureParameterSets = buffer.get().toPositiveInt()
            val pictureParameterSets = mutableListOf<ByteArray>()
            for (i in 0 until numPictureParameterSets) {
                val bytes = ByteArray(buffer.short.toInt())
                buffer.get(bytes)
                pictureParameterSets.add(bytes)
            }

            return AvcDecoderConfigurationRecord(
                configurationVersion = configurationVersion,
                avcProfileIndication = avcProfileIndication,
                profileCompatibility = profileCompatibility,
                avcLevelIndication = avcLevelIndication,
                lengthSizeMinusOneWithReserved = lengthSizeMinusOneWithReserved,
                numOfSequenceParameterSetsWithReserved = numOfSequenceParameterSetsWithReserved,
                sequenceParameterSets = sequenceParameterSets,
                pictureParameterSets = pictureParameterSets,
            )
        }
    }
}
