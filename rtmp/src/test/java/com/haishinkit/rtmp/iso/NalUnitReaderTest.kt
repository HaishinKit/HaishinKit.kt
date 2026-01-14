package com.haishinkit.rtmp.iso

import junit.framework.TestCase
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NalUnitReaderTest : TestCase() {
    fun testReader() {
        val byteBuffer =
            ByteBuffer.wrap(AVC_SPS_PPS_DATA).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
        byteBuffer.rewind()
        val units: List<NalUnit.Avc> = NalUnitReader.readAvc(byteBuffer)
        val pps = units.first { it.type == 8u.toUByte() }
        TestCase.assertEquals(0xca.toByte(), pps.payload.get())
        TestCase.assertEquals(0x43.toByte(), pps.payload.get())
        TestCase.assertEquals(0xc8.toByte(), pps.payload.get())
    }

    companion object {
        private var AVC_SPS_PPS_DATA =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67,
                0x42,
                0x00,
                0x29,
                0x8d.toByte(),
                0x8d.toByte(),
                0x40,
                0xa0.toByte(),
                0xfd.toByte(),
                0x00,
                0xf0.toByte(),
                0x88.toByte(),
                0x45,
                0x38,
                0x00,
                0x00,
                0x00,
                0x01,
                0x68,
                0xca.toByte(),
                0x43,
                0xc8.toByte(),
            )
    }
}
