package com.haishinkit.rtmp.iso

import java.nio.ByteBuffer

/**
 * This [AvcSequenceParameterSet] class represents the H.264 Sequence Parameter Set.
 * @see
 *  https://www.cardinalpeak.com/blog/the-h-264-sequence-parameter-set
 */
internal data class AvcSequenceParameterSet(
    val profileIdc: UByte,
    val levelIdc: UByte,
    val seqParameterSetId: UByte,
    val chromaFormatIdc: UByte?,
    val separateColourPlaneFlag: Boolean,
    val bitDepthLumaMinus8: UByte?,
    val bitDepthChromaMinus8: UByte?,
    val qpprimeYZeroTransformBypassFlag: Boolean,
    val seqScalingMatrixPresentFlag: Boolean,
    val log2MaxFrameNum: UByte,
    val picOrderCntType: UByte,
    val log2MaxPicOrderCntLsbMinus4: UByte?,
    val numRefFrames: UByte,
    val gapsInFrameNumValueAllowedFlag: Boolean,
    val picWidthInMbsMinus1: UByte,
    val picHeightInMapUnitsMinus1: UByte,
    val frameMbsOnlyFlag: Boolean,
    val direct8x8InferenceFlag: Boolean,
    val frameCroppingFlag: Boolean,
    val frameCropLeftOffset: UByte?,
    val frameCropRightOffset: UByte?,
    val frameCropTopOffset: UByte?,
    val frameCropBottomOffset: UByte?,
) {
    val videoWidth: Int
        get() {
            val frameCropLeftOffset = frameCropLeftOffset ?: 0u
            val frameCropRightOffset = frameCropRightOffset ?: 0u
            return ((picWidthInMbsMinus1 + 1u) * 16u - frameCropLeftOffset * 2u - frameCropRightOffset * 2u).toInt()
        }

    val videoHeight: Int
        get() {
            val frameCropTopOffset = frameCropTopOffset ?: 0u
            val frameCropBottomOffset = frameCropBottomOffset ?: 0u
            return (
                (
                    2u -
                        if (frameMbsOnlyFlag) {
                            1u
                        } else {
                            0u
                        }
                ) * (picHeightInMapUnitsMinus1 + 1u) * 16u - (
                    if (frameMbsOnlyFlag) {
                        2u
                    } else {
                        4u
                    }
                ) * (frameCropTopOffset + frameCropBottomOffset)
            ).toInt()
        }

    companion object {
        fun decode(buffer: ByteBuffer): AvcSequenceParameterSet {
            buffer.get()
            val profileIdc = buffer.get().toUByte()
            buffer.get()

            val levelIdc = buffer.get()
            val expGolombBuffer = ExpGolombBuffer.wrap(buffer)
            val seqParameterSetId = expGolombBuffer.int

            // high profile
            var chromaFormatIdc: Int? = null
            var separateColourPlaneFlag = false
            var bitDepthLumaMinus8: Int? = null
            var bitDepthChromaMinus8: Int? = null
            var qpprimeYZeroTransformBypassFlag = false
            var seqScalingMatrixPresentFlag = false
            when (profileIdc.toInt()) {
                100, 110, 122, 244, 44, 83, 86, 118, 128, 138 -> {
                    chromaFormatIdc = expGolombBuffer.int
                    if (chromaFormatIdc == 3) {
                        separateColourPlaneFlag = expGolombBuffer.boolean
                    }
                    bitDepthLumaMinus8 = expGolombBuffer.int
                    bitDepthChromaMinus8 = expGolombBuffer.int
                    qpprimeYZeroTransformBypassFlag = expGolombBuffer.boolean
                    seqScalingMatrixPresentFlag = expGolombBuffer.boolean
                }
            }

            val log2MaxFrameNum = expGolombBuffer.int
            val picOrderCntType = expGolombBuffer.int

            var log2MaxPicOrderCntLsbMinus4: UByte? = null
            when (picOrderCntType) {
                0 -> {
                    log2MaxPicOrderCntLsbMinus4 = expGolombBuffer.int.toUByte()
                }

                1 -> {
                }
            }

            val numRefFrames = expGolombBuffer.int
            val gapsInFrameNumValueAllowedFlag = expGolombBuffer.boolean
            val picWidthInMbsMinus1 = expGolombBuffer.int
            val picHeightInMapUnitsMinus1 = expGolombBuffer.int
            val frameMbsOnlyFlag = expGolombBuffer.boolean
            if (!frameMbsOnlyFlag) {
                expGolombBuffer.boolean
            }
            val direct8x8InferenceFlag = expGolombBuffer.boolean

            val frameCroppingFlag = expGolombBuffer.boolean
            val frameCropLeftOffset: Int?
            val frameCropRightOffset: Int?
            val frameCropTopOffset: Int?
            val frameCropBottomOffset: Int?

            if (frameCroppingFlag) {
                frameCropLeftOffset = expGolombBuffer.int
                frameCropRightOffset = expGolombBuffer.int
                frameCropTopOffset = expGolombBuffer.int
                frameCropBottomOffset = expGolombBuffer.int
            } else {
                frameCropLeftOffset = null
                frameCropRightOffset = null
                frameCropTopOffset = null
                frameCropBottomOffset = null
            }

            return AvcSequenceParameterSet(
                profileIdc = profileIdc,
                levelIdc = levelIdc.toUByte(),
                seqParameterSetId = seqParameterSetId.toUByte(),
                chromaFormatIdc = chromaFormatIdc?.toUByte(),
                separateColourPlaneFlag = separateColourPlaneFlag,
                bitDepthLumaMinus8 = bitDepthLumaMinus8?.toUByte(),
                bitDepthChromaMinus8 = bitDepthChromaMinus8?.toUByte(),
                qpprimeYZeroTransformBypassFlag = qpprimeYZeroTransformBypassFlag,
                seqScalingMatrixPresentFlag = seqScalingMatrixPresentFlag,
                log2MaxFrameNum = log2MaxFrameNum.toUByte(),
                picOrderCntType = picOrderCntType.toUByte(),
                log2MaxPicOrderCntLsbMinus4 = log2MaxPicOrderCntLsbMinus4,
                numRefFrames = numRefFrames.toUByte(),
                gapsInFrameNumValueAllowedFlag = gapsInFrameNumValueAllowedFlag,
                picWidthInMbsMinus1 = picWidthInMbsMinus1.toUByte(),
                picHeightInMapUnitsMinus1 = picHeightInMapUnitsMinus1.toUByte(),
                frameMbsOnlyFlag = frameMbsOnlyFlag,
                direct8x8InferenceFlag = direct8x8InferenceFlag,
                frameCroppingFlag = frameCroppingFlag,
                frameCropLeftOffset = frameCropLeftOffset?.toUByte(),
                frameCropRightOffset = frameCropRightOffset?.toUByte(),
                frameCropTopOffset = frameCropTopOffset?.toUByte(),
                frameCropBottomOffset = frameCropBottomOffset?.toUByte(),
            )
        }
    }
}
