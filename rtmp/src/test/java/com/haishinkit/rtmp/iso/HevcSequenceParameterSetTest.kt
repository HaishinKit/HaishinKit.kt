package com.haishinkit.rtmp.iso

import com.haishinkit.lang.decodeHex
import junit.framework.TestCase
import java.nio.ByteBuffer

class HevcSequenceParameterSetTest : TestCase() {
    fun testDecode() {
        val byteBuffer = ByteBuffer.wrap("010160000003000003000003000003005da006c201e1cde8b4aec92ee6a0202020571429".decodeHex())
        val sps = HevcSequenceParameterSet.decode(byteBuffer)
        assertEquals(1.toUByte(), sps.profileTierLevel.generalProfileIdc)
        assertEquals(93.toUByte(), sps.profileTierLevel.generalLevelIdc)
        assertEquals(true, sps.conformanceWindowFlag)
        assertEquals(864, sps.picWidthInLumaSamples)
        assertEquals(480, sps.picHeightIntLumaSamples)
    }
}
