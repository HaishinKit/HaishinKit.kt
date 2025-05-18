package com.haishinkit.rtmp.iso

import com.haishinkit.lang.decodeHex
import junit.framework.TestCase
import java.nio.ByteBuffer

class AvcSequenceParameterSetTest : TestCase() {
    fun testDecode() {
        val byteBuffer =
            ByteBuffer.wrap("6742000af841a2".decodeHex())
        val result = AvcSequenceParameterSet.decode(byteBuffer)
        assertEquals(128, result.videoWidth)
        assertEquals(96, result.videoHeight)
        assertEquals(
            AvcSequenceParameterSet(
                profileIdc = 0x42u,
                levelIdc = 0x0au,
                seqParameterSetId = 0u,
                chromaFormatIdc = null,
                separateColourPlaneFlag = false,
                bitDepthLumaMinus8 = null,
                bitDepthChromaMinus8 = null,
                qpprimeYZeroTransformBypassFlag = false,
                seqScalingMatrixPresentFlag = false,
                log2MaxFrameNum = 0u,
                picOrderCntType = 0u,
                log2MaxPicOrderCntLsbMinus4 = 0u,
                numRefFrames = 0u,
                gapsInFrameNumValueAllowedFlag = false,
                picWidthInMbsMinus1 = 7u,
                picHeightInMapUnitsMinus1 = 5u,
                frameMbsOnlyFlag = true,
                direct8x8InferenceFlag = false,
                frameCroppingFlag = false,
                frameCropLeftOffset = null,
                frameCropRightOffset = null,
                frameCropTopOffset = null,
                frameCropBottomOffset = null,
            ),
            result,
        )
    }

    // baseline 1920x1080 obs(macOS)
    fun testBaseline1920x1080() {
        val byteBuffer =
            ByteBuffer.wrap("6742c028db01e0089f97016a02020280000003008000001e478c1970".decodeHex())
        val result = AvcSequenceParameterSet.decode(byteBuffer)
        assertEquals(1920, result.videoWidth)
        assertEquals(1080, result.videoHeight)
    }

    // main 1920x1080 obs(macOS)
    fun testMain1920x1080() {
        val byteBuffer =
            ByteBuffer.wrap("674d4028eca03c0113f2e02d40404050000003001000000303c8f1831960".decodeHex())
        val result = AvcSequenceParameterSet.decode(byteBuffer)
        assertEquals(1920, result.videoWidth)
        assertEquals(1080, result.videoHeight)
    }

    // high 1920x1080 obs(macOS)
    fun testHigh1920x1080() {
        val byteBuffer =
            ByteBuffer.wrap("67640028acd940780227e5c05a808080a0000003002000000791e30632c0".decodeHex())
        val result = AvcSequenceParameterSet.decode(byteBuffer)
        assertEquals(1920, result.videoWidth)
        assertEquals(1080, result.videoHeight)
    }
}
