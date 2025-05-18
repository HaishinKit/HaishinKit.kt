package com.haishinkit.rtmp.iso

import java.nio.ByteBuffer

/**
 * This [HevcSequenceParameterSet] class represents the H.265 Sequence Parameter Set.
 * 7.3.2.2.1 General sequence parameter set RBSP syntax
 */
internal data class HevcSequenceParameterSet(
    val spsVideoParameterSetId: UByte,
    val spsMaxSubLayersMinus1: UByte,
    val spsTemporalIdNestingFlag: Boolean,
    val profileTierLevel: HevcProfileTierLevel,
    val spsSeqParmeterSetId: Int,
    val chromaFormatIdc: UByte,
    val separateColourPlanFlag: Boolean,
    val picWidthInLumaSamples: Int,
    val picHeightIntLumaSamples: Int,
    val conformanceWindowFlag: Boolean,
    val confWinLeftOffset: Int?,
    val confWinRightOffset: Int?,
    val confWinTopOffset: Int?,
    val confWinBottomOffset: Int?,
    val bitDepthLumaMinus8: UByte,
    val bitDepthChromaMinus8: UByte,
    // There is other data, but it is unnecessary so I will omit it.
) {
    companion object {
        fun decode(buffer: ByteBuffer): HevcSequenceParameterSet {
            val rbsp = IsoTypeBufferUtils.ebsp2rbsp(buffer)
            val isoTypeBuffer = IsoTypeBuffer(rbsp)

            val spsVideoParameterSetId = isoTypeBuffer.get(4)
            val spsMaxSubLayersMinus1 = isoTypeBuffer.get(3)
            val spsTemporalIdNestingFlag = isoTypeBuffer.boolean

            val profileTierLevel = HevcProfileTierLevel.decode(rbsp, spsMaxSubLayersMinus1.toInt())
            val expGolombBuffer = ExpGolombBuffer.wrap(rbsp)
            val spsSeqParmeterSetId = expGolombBuffer.int
            val chromaFormatIdc = expGolombBuffer.int
            val separateColourPlanFlag =
                if (chromaFormatIdc == 3) {
                    expGolombBuffer.boolean
                } else {
                    false
                }
            val picWidthInLumaSamples = expGolombBuffer.int
            val picHeightIntLumaSamples = expGolombBuffer.int

            val conformanceWindowFlag = expGolombBuffer.boolean
            var confWinLeftOffset: Int? = null
            var confWinRightOffset: Int? = null
            var confWinTopOffset: Int? = null
            var confWinBottomOffset: Int? = null
            if (conformanceWindowFlag) {
                confWinLeftOffset = expGolombBuffer.int
                confWinRightOffset = expGolombBuffer.int
                confWinTopOffset = expGolombBuffer.int
                confWinBottomOffset = expGolombBuffer.int
            }

            val bitDepthLumaMinus8 = expGolombBuffer.int
            val bitDepthChromaMinus8 = expGolombBuffer.int

            return HevcSequenceParameterSet(
                spsVideoParameterSetId = spsVideoParameterSetId,
                spsMaxSubLayersMinus1 = spsMaxSubLayersMinus1,
                spsTemporalIdNestingFlag = spsTemporalIdNestingFlag,
                profileTierLevel = profileTierLevel,
                spsSeqParmeterSetId = spsSeqParmeterSetId,
                chromaFormatIdc = chromaFormatIdc.toUByte(),
                separateColourPlanFlag = separateColourPlanFlag,
                picWidthInLumaSamples = picWidthInLumaSamples,
                picHeightIntLumaSamples = picHeightIntLumaSamples,
                conformanceWindowFlag = conformanceWindowFlag,
                confWinLeftOffset = confWinLeftOffset,
                confWinRightOffset = confWinRightOffset,
                confWinTopOffset = confWinTopOffset,
                confWinBottomOffset = confWinBottomOffset,
                bitDepthLumaMinus8 = bitDepthLumaMinus8.toUByte(),
                bitDepthChromaMinus8 = bitDepthChromaMinus8.toUByte(),
            )
        }
    }
}
