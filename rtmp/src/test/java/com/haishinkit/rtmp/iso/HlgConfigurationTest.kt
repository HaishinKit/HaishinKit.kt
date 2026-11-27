package com.haishinkit.rtmp.iso

import android.media.MediaFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HlgConfigurationTest {
    @Test fun buildsMain10Av1cFromRealEncoderSequenceObu() {
        val bytes = javaClass.getResourceAsStream("/hdr/pixel8-hlg-sequence.obu")!!.readBytes()
        val payload = ByteBuffer.wrap(bytes).apply { position(2) }.slice()
        val info = Av1SequenceHeader.parse(payload)
        assertEquals(10, info.bitDepth)
        assertEquals(9, info.colorPrimaries)
        assertEquals(18, info.transfer)
        assertEquals(9, info.matrix)
        val format = MediaFormat.createVideoFormat("video/av01", 320, 180).apply { setByteBuffer("csd-0", ByteBuffer.wrap(bytes)) }
        val configuration = DecoderConfigurationRecord.create("video/av01", format)!!
        val actual = ByteArray(configuration.capacity).also { configuration.toByteBuffer().get(it) }
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0, 0x4c, 0) + bytes, actual)
        assertEquals(configuration.toByteBuffer(), DecoderConfigurationRecord.decode("video/av01", configuration.toByteBuffer())!!.toByteBuffer())
    }

    @Test fun completesHeaderOnlyAv1cWithoutIncludingFrameObus() {
        val bytes = javaClass.getResourceAsStream("/hdr/pixel8-hlg-sequence.obu")!!.readBytes()
        val header = byteArrayOf(0x81.toByte(), 0, 0x4c, 0)
        val record = Av1DecoderConfigurationRecord.decode(ByteBuffer.wrap(header))
        val frame = ByteBuffer.wrap(byteArrayOf(0x12, 0) + bytes + byteArrayOf(0x32, 1, 0))
        val completed = record.withSequenceHeader(frame)!!
        assertEquals(0, frame.position())
        assertEquals(ByteBuffer.wrap(header + bytes), completed.toByteBuffer())
        assertEquals(false, record.hasSequenceHeader)
        assertEquals(true, completed.hasSequenceHeader)
        org.junit.Assert.assertNull(record.withSequenceHeader(ByteBuffer.wrap(byteArrayOf(0x32, 1, 0))))
    }

    @Test fun fullRangeHlgIsNotAcceptedByTheLimitedRangePath() {
        // Reduced still-picture sequence, Main10, 320x180, BT.2020/HLG; only color_range differs.
        fun record(hex: String) = Av1DecoderConfigurationRecord.decode(ByteBuffer.wrap(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        assertEquals(true, record("81004c000a0a1821e7f6605091209040").isHlg)
        assertEquals(false, record("81004c000a0a1821e7f6605091209840").isHlg)
    }

    @Test fun keepsHeaderOnlyAv1cFromPixelEncoder() {
        val bytes = byteArrayOf(0x81.toByte(), 0, 0x4c, 0)
        val format = MediaFormat.createVideoFormat("video/av01", 320, 180).apply { setByteBuffer("csd-0", ByteBuffer.wrap(bytes)) }
        assertEquals(ByteBuffer.wrap(bytes), DecoderConfigurationRecord.create("video/av01", format)!!.toByteBuffer())
    }

    @Test fun hevcSequenceRecordRetainsTenBitConfiguration() {
        val bytes = "0000000140010c01ffff0220000003000003000003000003003c2c09000000014201010220000003000003000003000003003ca00a080c1f3b68b4aec12ee6a1224120571429000000014401c06670c8640cc640".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val format = MediaFormat.createVideoFormat("video/hevc", 320, 180).apply { setByteBuffer("csd-0", ByteBuffer.wrap(bytes)) }
        val record = DecoderConfigurationRecord.create("video/hevc", format) as HevcDecoderConfigurationRecord
        assertEquals(2.toUByte(), record.bitDepthLumaMinus8)
        assertEquals(2.toUByte(), record.bitDepthChromaMinus8)
        val received = DecoderConfigurationRecord.decode("video/hevc", record.toByteBuffer()) as HevcDecoderConfigurationRecord
        assertEquals(record.bitDepthLumaMinus8, received.bitDepthLumaMinus8)
        assertEquals(record.videoSize, received.videoSize)
        assertEquals(record.toByteBuffer(), record.toByteBuffer())
        record.toCodecSpecificData(emptyList())
        assertEquals(record.videoSize, received.videoSize)
    }
}
