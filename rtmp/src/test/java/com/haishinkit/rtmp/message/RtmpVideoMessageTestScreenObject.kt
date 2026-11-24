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

    fun testAv1PacketsPreserveObusWithoutCompositionTimeOrNalConversion() {
        val obu = byteArrayOf(0x12, 0, 0x32, 4, 0, 0, 1, 0x55)
        for (packetType in listOf(0, 1)) {
            val message =
                RtmpVideoMessage().apply {
                    isExHeader = true
                    fourCC = 0x61763031
                    frame = 1
                    this.packetType = packetType.toByte()
                    data = ByteBuffer.wrap(obu)
                    compositeTime = 1234
                }
            assertEquals(5 + obu.size, message.length)
            val packet = ByteBuffer.allocate(message.length)
            message.encode(packet)
            packet.flip()
            assertEquals((0x90 or packetType).toByte(), packet.get(0))
            val received = RtmpVideoMessage().apply { length = packet.remaining() }
            received.decode(packet)
            assertEquals(0, received.compositeTime)
            assertEquals(0x61763031, received.fourCC)
            assertEquals(ByteBuffer.wrap(obu), received.data)
            assertFalse(packet.hasRemaining())
        }
    }

    private fun verifyCompositeTime(
        time: Int,
        enhanced: Boolean,
    ) {
        val buffer = packet(time, enhanced)
        val message = RtmpVideoMessage()
        message.length = buffer.remaining()
        message.decode(buffer)
        assertEquals(time, message.compositeTime)
        assertEquals(1.toByte(), message.packetType)
        assertEquals(0x55.toByte(), message.data!!.get())
        assertFalse(buffer.hasRemaining())
    }

    private fun packet(
        time: Int,
        enhanced: Boolean,
    ): ByteBuffer {
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
