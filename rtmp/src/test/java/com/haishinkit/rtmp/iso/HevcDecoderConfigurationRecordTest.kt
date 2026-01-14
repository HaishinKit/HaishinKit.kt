package com.haishinkit.rtmp.iso

import com.haishinkit.lang.decodeHex
import com.haishinkit.util.toHexString
import junit.framework.TestCase
import java.nio.ByteBuffer

class HevcDecoderConfigurationRecordTest : TestCase() {
    fun testMediaFormat() {
        val buffer = ByteBuffer.wrap(CSD0.decodeHex())
        val record = HevcDecoderConfigurationRecord.create(buffer)
        assertEquals(1, record.configurationVersion.toByte())
        assertEquals(93, record.generalLevelIdc.toByte())
        assertEquals(false, record.temporalIdNested)
        print(record.toByteBuffer().toHexString())
    }

    fun testDecode() {
        val buffer = ByteBuffer.wrap(RECORD_1.decodeHex())
        val decode = HevcDecoderConfigurationRecord.decode(buffer)
        assertEquals(1, decode.configurationVersion.toByte())
        assertEquals(1610612736, decode.generalProfileCompatibilityFlags.toInt())
        assertEquals(193514046488576, decode.generalConstraintIndicatorFlags.toLong())
    }

    fun testDecode_2() {
        val buffer = ByteBuffer.wrap(RECORD_2.decodeHex())
        val decode = HevcDecoderConfigurationRecord.decode(buffer)
    }

    fun testEncode() {
        val decode = ByteBuffer.wrap(RECORD_1.decodeHex())
        val record = HevcDecoderConfigurationRecord.decode(decode)
        val encode = record.toByteBuffer()
        assertEquals(RECORD_1, encode.toHexString())
    }

    fun testEncode_2() {
        val decode = ByteBuffer.wrap(RECORD_2.decodeHex())
        val record = HevcDecoderConfigurationRecord.decode(decode)
        val encode = record.toByteBuffer()
        assertEquals(RECORD_2, encode.toHexString())
    }

    fun testToCodecSpecificData() {
        val buffer = ByteBuffer.wrap(RECORD_1.decodeHex())
        val decode = HevcDecoderConfigurationRecord.decode(buffer)
        val csd0 = decode.toCodecSpecificData(emptyList()).firstOrNull { it.key == "csd-0" } ?: return
        assertEquals(
            "0000000140010c01ffff016000000300b0000003000003005d15c09000000001420101016000000300b0000003000003005da00280802d162057b91655350101010080000000014401c02cbc14c9",
            (csd0.value as ByteBuffer).toHexString(),
        )
    }

    companion object {
        const val CSD0 = "0000000140010c01ffff016000000300b0000003000003005dac0900000001420101016000000300b0000003000003005da006c201e1cde5aee4c92ea00bb4284a000000014401c0e30f033084"
        const val RECORD_1 = "010160000000b000000000005df000fcfdf8f800000f03200001001840010c01ffff016000000300b0000003000003005d15c0902100010023420101016000000300b0000003000003005da00280802d162057b9165535010101008022000100074401c02cbc14c9"
        const val RECORD_2 = "012160000000b000000000005df000fcfdf8f800000f03200001001840010c01ffff216000000300b0000003000003005d11c090210001002c420101216000000300b0000003000003005da00280802d16711e4912233924925527e86b4ca489a80808080422000100084401c0252f053240"
    }
}
