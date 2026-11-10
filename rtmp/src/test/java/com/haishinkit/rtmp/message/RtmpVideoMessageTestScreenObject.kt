package com.haishinkit.rtmp.message

import junit.framework.TestCase
import java.nio.ByteBuffer

class RtmpVideoMessageTestScreenObject : TestCase() {
    fun testDecodeAvcCompositeTime() {
        for (time in listOf(-8388608, -1120, -910, -1, 0, 1, 1120, 8388607)) {
            verifyCompositeTime(time, enhanced = false)
        }
    }

    fun testDecodeHevcCompositeTime() {
        for (time in listOf(-8388608, -1120, -910, -1, 0, 1, 1120, 8388607)) {
            verifyCompositeTime(time, enhanced = true)
        }
    }

    fun testDecodeWithoutCompositeTimeResetsReusedMessage() {
        val message = RtmpVideoMessage()
        val codedFrames = packet(1120, enhanced = true)
        message.length = codedFrames.remaining()
        message.decode(codedFrames)
        assertEquals(1120, message.compositeTime)

        // Enhanced HEVC CodedFramesX carries no composition-time field.
        val codedFramesX = ByteBuffer.wrap(byteArrayOf(0x93.toByte(), 0x68, 0x76, 0x63, 0x31, 0x55))
        message.length = codedFramesX.remaining()
        message.decode(codedFramesX)
        assertEquals(0, message.compositeTime)
        assertEquals(0x55.toByte(), message.data!!.get())
    }

    private fun verifyCompositeTime(time: Int, enhanced: Boolean) {
        val buffer = packet(time, enhanced)
        val message = RtmpVideoMessage()
        message.length = buffer.remaining()
        message.decode(buffer)
        assertEquals(time, message.compositeTime)
        assertEquals(1.toByte(), message.packetType)
        assertEquals(0x55.toByte(), message.data!!.get())
        assertFalse(buffer.hasRemaining())
    }

    private fun packet(time: Int, enhanced: Boolean): ByteBuffer {
        val buffer = ByteBuffer.allocate(if (enhanced) 9 else 6)
        if (enhanced) {
            buffer.put(0x91.toByte()).putInt(0x68766331) // HEVC CodedFrames.
        } else {
            buffer.put(0x17.toByte()).put(1.toByte()) // AVC NALU.
        }
        buffer.put((time shr 16).toByte()).put((time shr 8).toByte()).put(time.toByte())
        buffer.put(0x55.toByte()).flip()
        return buffer
    }
}
