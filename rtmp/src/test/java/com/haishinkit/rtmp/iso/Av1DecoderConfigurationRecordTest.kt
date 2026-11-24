package com.haishinkit.rtmp.iso

import android.util.Size
import com.haishinkit.codec.CodecOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Av1DecoderConfigurationRecordTest {
    // SVT-AV1 encoded 320x180 testsrc2, extracted from the MP4 av1C box.
    private val fixture = "81000c000a0b0200000521e7f66017c002".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun acceptsMediaMtxConfiguration() {
        // Captured from a local MediaMTX v1.21.4 RTMP reader connection.
        val bytes = "81080c000a0b00000042abbfc3700be001".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(Size(1920, 1080), Av1DecoderConfigurationRecord.decode(ByteBuffer.wrap(bytes)).videoSize)
    }

    @Test
    fun parsesDimensionsAndPreservesConfiguration() {
        val input = ByteBuffer.wrap(fixture)
        val record = DecoderConfigurationRecord.decode("video/av01", input)!!
        assertEquals(Size(320, 180), record.videoSize)
        assertFalse(input.hasRemaining())
        val encoded = ByteArray(record.capacity).also { record.toByteBuffer().get(it) }
        assertArrayEquals(fixture, encoded)
        val options = record.toCodecSpecificData(listOf(CodecOption("csd-0", ByteBuffer.allocate(1)), CodecOption("csd-1", ByteBuffer.allocate(1)), CodecOption("test", 1)))
        assertEquals(1, options.count { it.key == "csd-0" })
        assertFalse(options.any { it.key == "csd-1" })
        assertEquals(1, options.first { it.key == "test" }.value)
        assertEquals(ByteBuffer.wrap(fixture), options.first { it.key == "csd-0" }.value)
    }

    @Test
    fun allowsConfigurationWithoutSequenceObu() {
        assertNull(Av1DecoderConfigurationRecord.decode(ByteBuffer.wrap(fixture.copyOf(4))).videoSize)
    }

    @Test
    fun rejectsMalformedConfigurations() {
        for (bytes in listOf(byteArrayOf(), fixture.copyOf(3), fixture.copyOf(8), byteArrayOf(1, 0, 0, 0), byteArrayOf(-127, 0, 0, -32))) {
            assertThrows(IllegalArgumentException::class.java) { Av1DecoderConfigurationRecord.decode(ByteBuffer.wrap(bytes)) }
        }
    }

    @Test
    fun readsOnlyRemainingBufferRange() {
        val buffer = ByteBuffer.allocate(fixture.size + 8).putInt(123).put(fixture).putInt(456)
        buffer.position(4)
        buffer.limit(4 + fixture.size)
        assertEquals(Size(320, 180), Av1DecoderConfigurationRecord.decode(buffer).videoSize)
    }
}
