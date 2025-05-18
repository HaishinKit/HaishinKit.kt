package com.haishinkit.rtmp.message

import junit.framework.TestCase
import java.nio.ByteBuffer

class RtmpVideoMessageTestScreenObject : TestCase() {
    fun testGetCompositeTime() {
        val message1 = RtmpVideoMessage()
        message1.data = ByteBuffer.wrap(byteArrayOf(0, 255.toByte(), 251.toByte(), 160.toByte()))
        assertEquals(-1120, message1.compositeTime)

        val message2 = RtmpVideoMessage()
        message2.data = ByteBuffer.wrap(byteArrayOf(0, 255.toByte(), 252.toByte(), 114.toByte()))
        assertEquals(-910, message2.compositeTime)
    }
}
