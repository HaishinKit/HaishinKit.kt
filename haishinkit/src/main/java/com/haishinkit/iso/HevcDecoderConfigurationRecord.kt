package com.haishinkit.iso

import android.media.MediaFormat
import android.util.Size
import com.haishinkit.codec.CodecOption
import java.nio.ByteBuffer

// / ISO/IEC 14496-15 8.3.3.1.2
internal data class HevcDecoderConfigurationRecord(
    val configurationVersion: UByte,
    val generalProfileSpace: UByte,
    val generalTierFlag: Boolean,
    val generalProfileIdc: UByte,
    val generalProfileCompatibilityFlags: UInt,
    val generalConstraintIndicatorFlags: ULong,
    val generalLevelIdc: UByte,
    val minSpatialSegmentationIdc: UShort,
    val parallelismType: UByte,
    val chromaFormat: UByte,
    val bitDepthLumaMinus8: UByte,
    val bitDepthChromaMinus8: UByte,
    val avgFrameRate: UShort,
    val constantFrameRate: UByte,
    val numTemporalLayers: UByte,
    val temporalIdNested: Boolean,
    val lengthSizeMinusOne: UByte,
    val numberOfArrays: UByte,
    val arrays: Map<UByte, List<NalUnit.Hevc>>,
) : DecoderConfigurationRecord {
    override val mime: String
        get() = MediaFormat.MIMETYPE_VIDEO_HEVC
    override val capacity: Int
        get() {
            var capacity = 23
            for (units in arrays) {
                capacity += 3
                for (unit in units.value) {
                    capacity += 2
                    capacity += unit.length
                }
            }
            return capacity
        }

    override val videoSize: Size?
        get() {
            val payload = arrays[NAL_UNIT_TYPE_SPS]?.firstOrNull()?.payload ?: return null
            val sequenceParameterSet = HevcSequenceParameterSet.decode(payload)
            return Size(
                sequenceParameterSet.picWidthInLumaSamples,
                sequenceParameterSet.picHeightIntLumaSamples,
            )
        }

    override fun encode(buffer: ByteBuffer): HevcDecoderConfigurationRecord {
        val isoTypeBuffer = IsoTypeBuffer(buffer)
        isoTypeBuffer.putUByte(configurationVersion)
        isoTypeBuffer.putUByte(
            (generalProfileSpace.toUInt() shr 6).toUByte() or
                (
                    if (generalTierFlag) {
                        0x20
                    } else {
                        0
                    }
                ).toUByte() or (generalProfileIdc),
        )
        isoTypeBuffer.putUInt(generalProfileCompatibilityFlags)
        isoTypeBuffer.putUInt48(generalConstraintIndicatorFlags)
        isoTypeBuffer.putUByte(generalLevelIdc)
        isoTypeBuffer.putUShort((RESERVED1 shl 12).toUShort() or minSpatialSegmentationIdc)
        isoTypeBuffer.putUByte((RESERVED2 shl 2).toUByte() or parallelismType)
        isoTypeBuffer.putUByte((RESERVED3 shl 2).toUByte() or chromaFormat)
        isoTypeBuffer.putUByte((RESERVED4 shl 3).toUByte() or bitDepthLumaMinus8)
        isoTypeBuffer.putUByte((RESERVED5 shl 3).toUByte() or bitDepthChromaMinus8)
        isoTypeBuffer.putUShort(avgFrameRate)
        isoTypeBuffer.putUByte(
            (constantFrameRate.toUInt() shl 6) or (numTemporalLayers.toUInt() shl 3) or (
                if (temporalIdNested) {
                    0x4
                } else {
                    0
                }
            ).toUInt() + lengthSizeMinusOne.toUInt(),
        )
        isoTypeBuffer.putUByte(arrays.size)
        for (units in arrays.toSortedMap()) {
            isoTypeBuffer.putUByte(units.key)
            isoTypeBuffer.putUShort(units.value.size)
            for (unit in units.value) {
                isoTypeBuffer.putUShort(unit.length)
                unit.encode(isoTypeBuffer)
            }
        }
        return this
    }

    override fun toCodecSpecificData(options: List<CodecOption>): List<CodecOption> {
        val mutableOptions = mutableListOf<CodecOption>()
        mutableOptions.addAll(options.filter { it.key != CSD0 })
        var capacity = 0
        for (units in arrays) {
            for (unit in units.value) {
                capacity += 6 + unit.payload.remaining()
            }
        }
        val buffer = ByteBuffer.allocate(capacity)
        for (units in arrays) {
            for (unit in units.value) {
                buffer.put(IsoTypeBufferUtils.START_CODE)
                buffer.put((unit.type.toUInt() shl 1).toByte())
                buffer.put(unit.temporalIdPlusOne.toByte())
                buffer.put(unit.payload)
            }
        }
        buffer.flip()
        mutableOptions.add(CodecOption(CSD0, buffer))
        return mutableOptions
    }

    companion object : DecoderConfigurationRecordFactory {
        const val NAL_UNIT_TYPE_VPS: UByte = 32u
        const val NAL_UNIT_TYPE_SPS: UByte = 33u
        const val NAL_UNIT_TYPE_PPS: UByte = 34u
        const val CSD0 = "csd-0"

        private const val RESERVED1 = 0xFF
        private const val RESERVED2 = 0x3F
        private const val RESERVED3 = 0x3F
        private const val RESERVED4 = 0x1F
        private const val RESERVED5 = 0x1F
        private var TAG = HevcDecoderConfigurationRecord::class.java.simpleName

        fun create(buffer: ByteBuffer): HevcDecoderConfigurationRecord {
            val units: List<NalUnit.Hevc> = NalUnitReader.readHevc(buffer)
            val arrays = mutableMapOf<UByte, List<NalUnit.Hevc>>()
            arrays[NAL_UNIT_TYPE_VPS] = units.filter { it.type == NAL_UNIT_TYPE_VPS }
            arrays[NAL_UNIT_TYPE_SPS] = units.filter { it.type == NAL_UNIT_TYPE_SPS }
            arrays[NAL_UNIT_TYPE_PPS] = units.filter { it.type == NAL_UNIT_TYPE_PPS }
            val spsUnit =
                units.firstOrNull { it.type == NAL_UNIT_TYPE_SPS }
                    ?: throw IllegalArgumentException()
            val sps = HevcSequenceParameterSet.decode(spsUnit.payload)
            return HevcDecoderConfigurationRecord(
                configurationVersion = 1u,
                generalProfileSpace = sps.profileTierLevel.generalProfileSpace,
                generalTierFlag = sps.profileTierLevel.generalTierFlag,
                generalProfileIdc = sps.profileTierLevel.generalProfileIdc,
                generalProfileCompatibilityFlags = sps.profileTierLevel.generalProfileCompatFlags,
                generalConstraintIndicatorFlags = sps.profileTierLevel.generalConstraintIndicatorFlags,
                generalLevelIdc = sps.profileTierLevel.generalLevelIdc,
                minSpatialSegmentationIdc = 0u,
                parallelismType = 0u,
                chromaFormat = sps.chromaFormatIdc,
                bitDepthLumaMinus8 = sps.bitDepthLumaMinus8,
                bitDepthChromaMinus8 = sps.bitDepthChromaMinus8,
                avgFrameRate = 0u,
                constantFrameRate = 0u,
                numTemporalLayers = 0u,
                temporalIdNested = false,
                lengthSizeMinusOne = 3u,
                numberOfArrays = units.size.toUByte(),
                arrays = arrays,
            )
        }

        override fun create(mediaFormat: MediaFormat): HevcDecoderConfigurationRecord =
            create(mediaFormat.getByteBuffer(CSD0) ?: throw IllegalArgumentException())

        override fun decode(buffer: ByteBuffer): HevcDecoderConfigurationRecord {
            val isoTypeBuffer = IsoTypeBuffer(buffer)
            val configurationVersion = isoTypeBuffer.getUByte()
            val generalProfileSpace = isoTypeBuffer.get(2)
            val generalTierFlag = isoTypeBuffer.boolean
            val generalProfileIdc = isoTypeBuffer.get(5)
            val generalProfileCompatibilityFlags = isoTypeBuffer.getUInt()
            val generalConstraintIndicatorFlags = isoTypeBuffer.getULong(48)
            val generalLevelIdc = isoTypeBuffer.getUByte()
            val minSpatialSegmentationIdc = isoTypeBuffer.getUShort() and 0xfffu
            val parallelismType = isoTypeBuffer.getUByte() and 0x3u
            val chromaFormat = isoTypeBuffer.getUByte() and 0x3u
            val bitDepthLumaMinus8 = isoTypeBuffer.getUByte() and 0x7u
            val bitDepthChromaMinus8 = isoTypeBuffer.getUByte() and 0x7u
            val avgFrameRate = isoTypeBuffer.getUShort()
            val constantFrameRate = isoTypeBuffer.get(2)
            val numTemporalLayers = isoTypeBuffer.get(3)
            val temporalIdNested = isoTypeBuffer.boolean
            val lengthSizeMinusOne = isoTypeBuffer.get(2)
            val numberOfArrays = isoTypeBuffer.getUByte()
            val arrays = mutableMapOf<UByte, List<NalUnit.Hevc>>()
            for (i in 0 until numberOfArrays.toInt()) {
                val type = isoTypeBuffer.getUByte()
                val numNalus = isoTypeBuffer.getUShort().toInt()
                val units = mutableListOf<NalUnit.Hevc>()
                for (j in 0 until numNalus) {
                    val length = isoTypeBuffer.getUShort().toInt()
                    units.add(NalUnit.Hevc.create(isoTypeBuffer.getBytes(length)))
                }
                arrays[type] = units
            }
            return HevcDecoderConfigurationRecord(
                configurationVersion = configurationVersion,
                generalProfileSpace = generalProfileSpace,
                generalTierFlag = generalTierFlag,
                generalProfileIdc = generalProfileIdc,
                generalProfileCompatibilityFlags = generalProfileCompatibilityFlags,
                generalConstraintIndicatorFlags = generalConstraintIndicatorFlags,
                generalLevelIdc = generalLevelIdc,
                minSpatialSegmentationIdc = minSpatialSegmentationIdc,
                parallelismType = parallelismType,
                chromaFormat = chromaFormat,
                bitDepthLumaMinus8 = bitDepthLumaMinus8,
                bitDepthChromaMinus8 = bitDepthChromaMinus8,
                avgFrameRate = avgFrameRate,
                constantFrameRate = constantFrameRate,
                numTemporalLayers = numTemporalLayers,
                temporalIdNested = temporalIdNested,
                lengthSizeMinusOne = lengthSizeMinusOne,
                numberOfArrays = numberOfArrays,
                arrays = arrays,
            )
        }
    }
}
