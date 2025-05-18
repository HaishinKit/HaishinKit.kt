package com.haishinkit.rtmp.util

import com.haishinkit.lang.decodeHex
import com.haishinkit.rtmp.iso.IsoTypeBufferUtils
import com.haishinkit.util.toHexString
import junit.framework.TestCase
import java.nio.ByteBuffer

class IsoTypeBufferUtilTest : TestCase() {
    fun testEbsp2rbsp() {
        val byteBuffer = ByteBuffer.wrap("01010160000003000003000003000003005da006c201e1cde8b4aec92ee6a0202020571429".decodeHex())
        print(IsoTypeBufferUtils.ebsp2rbsp(byteBuffer).toHexString())
    }
}
