package com.haishinkit.rtmp.util

import com.haishinkit.rtmp.iso.IsoTypeBuffer
import junit.framework.TestCase
import java.nio.ByteBuffer

class IsoTypeBufferTest : TestCase() {
    fun testMain() {
        val buffer =
            ByteBuffer.allocate(10).apply {
                put(0b11010000u.toByte())
                put(0b01000000)
                put(0b00010000)
                put(0b00010000)
                put(0b00010000)
                put(0b01010000)
                put(0b01000000)
                put(0b00010000)
                put(0b00010000)
                put(0b00010000)
                flip()
            }
        val bitBuffer = IsoTypeBuffer(buffer)
        assertEquals(true, bitBuffer.boolean)
        assertEquals(true, bitBuffer.boolean)
        assertEquals(false, bitBuffer.boolean)
        assertEquals(true, bitBuffer.boolean)
    }

    fun testUInt() {
        val buffer =
            ByteBuffer.allocate(2).apply {
                put(0b11010000u.toByte())
                put(0b11111010u.toByte())
                flip()
            }
        val bitBuffer = IsoTypeBuffer(buffer)
        assertEquals(0b11u.toUByte(), bitBuffer.get(2))
        assertEquals(0b0u.toUByte(), bitBuffer.get(1))
        assertEquals(0b10000u.toUByte(), bitBuffer.get(5))
        assertEquals(0b11111u.toUByte(), bitBuffer.get(5))
        assertEquals(0b01u.toUByte(), bitBuffer.get(2))
        assertEquals(0b0u.toUByte(), bitBuffer.get(1))
    }

    fun testULong() {
        val buffer =
            ByteBuffer.allocate(10).apply {
                putLong(1000L)
                flip()
            }
        val bitBuffer = IsoTypeBuffer(buffer)
        bitBuffer.getULong(48)
    }

    fun testGetBooleanAndSkip() {
        val buffer =
            ByteBuffer.allocate(10).apply {
                put(0b11010000u.toByte())
                put(0b01001111)
                put(0b00010000)
                put(0b01001110)
                put(0b00001000)
                put(0b01010000)
                put(0b01000000)
                put(0b00010000)
                put(0b00010000)
                put(0b00010000)
                flip()
            }
        val bitBuffer = IsoTypeBuffer(buffer)
        bitBuffer.skip(2)
        assertEquals(false, bitBuffer.boolean)
        assertEquals(true, bitBuffer.boolean)
        bitBuffer.skip(8 + 2)
        assertEquals(true, bitBuffer.boolean)
        assertEquals(true, bitBuffer.boolean)
    }
}
