package com.haishinkit.rtmp.iso

import android.media.MediaFormat
import android.util.Size
import com.haishinkit.codec.CodecOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class VpDecoderConfigurationRecordTest {
    @Test
    fun tabletKeyframesProduceConfigurationWithoutConsumingInput() {
        for (name in listOf("vp8", "vp9")) {
            val mime = "video/x-vnd.on2.$name"
            val bytes = javaClass.getResourceAsStream("/vpx/$name-keyframe.bin")!!.use { it.readBytes() }
            val frame =
                ByteBuffer.wrap(byteArrayOf(7) + bytes + byteArrayOf(8)).apply {
                    position(1)
                    limit(bytes.size + 1)
                }
            val record = VpDecoderConfigurationRecord.fromKeyFrame(mime, frame)
            assertEquals(1, frame.position())
            assertEquals(Size(1280, 720), record.videoSize)
            assertEquals(0, record.profile)
            assertEquals(8, record.bitDepth)
            assertEquals(12, record.capacity)
            assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0x82.toByte(), 2, 2, 2, 0, 0), record.toByteBuffer().array())
        }
    }

    @Test
    fun bareAndFfmpegConfigurationRecordsNormalizeToTheSameHeader() {
        val bare = byteArrayOf(0, 31, 0x82.toByte(), 2, 2, 2, 0, 0)
        val boxed = byteArrayOf(1, 0, 0, 0) + bare
        assertArrayEquals(boxed, VpDecoderConfigurationRecord.decode(MediaFormat.MIMETYPE_VIDEO_VP9, ByteBuffer.wrap(byteArrayOf(1) + bare)).toByteBuffer().array())
        val record = VpDecoderConfigurationRecord.decode(MediaFormat.MIMETYPE_VIDEO_VP9, ByteBuffer.wrap(bare))
        assertArrayEquals(boxed, record.toByteBuffer().array())
        assertArrayEquals(boxed, VpDecoderConfigurationRecord.decode(MediaFormat.MIMETYPE_VIDEO_VP9, ByteBuffer.wrap(boxed)).toByteBuffer().array())
        val options = record.toCodecSpecificData(listOf(CodecOption("csd-0", ByteBuffer.allocate(4)), CodecOption("csd-1", ByteBuffer.allocate(4)), CodecOption("vendor-option", 7)))
        assertFalse(options.any { it.key.startsWith("csd-") })
        assertTrue(options.contains(CodecOption("vendor-option", 7)))
        assertTrue(options.contains(CodecOption(MediaFormat.KEY_PROFILE, 1)))
    }

    @Test
    fun realProfile2FrameKeepsTenBitProfileAndDimensions() {
        val bytes = javaClass.getResourceAsStream("/vpx/vp9-profile2-keyframe.bin")!!.use { it.readBytes() }
        val record = VpDecoderConfigurationRecord.fromKeyFrame(MediaFormat.MIMETYPE_VIDEO_VP9, ByteBuffer.wrap(bytes))
        assertEquals(2, record.profile)
        assertEquals(10, record.bitDepth)
        assertEquals(Size(320, 180), record.videoSize)
        assertTrue(record.toCodecSpecificData(emptyList()).contains(CodecOption(MediaFormat.KEY_PROFILE, 4)))
    }

    @Test
    fun malformedOrUnsupportedConfigurationIsRejected() {
        val valid = byteArrayOf(0, 0, 0x82.toByte(), 2, 2, 2, 0, 0)
        val cases =
            listOf(
                valid.copyOf(7), valid + byteArrayOf(0),
                valid.clone().apply { this[0] = 4 },
                valid.clone().apply { this[1] = 99 },
                valid.clone().apply { this[2] = 0xA2.toByte() },
                valid.clone().apply { this[2] = 0x8E.toByte() },
                valid.clone().apply { this[5] = 0 },
                valid.clone().apply { this[7] = 1 },
                byteArrayOf(2, 0, 0, 0) + valid,
                byteArrayOf(1, 0, 0, 1) + valid,
            )
        for (bytes in cases) assertThrows(IllegalArgumentException::class.java) {
            VpDecoderConfigurationRecord.decode(MediaFormat.MIMETYPE_VIDEO_VP9, ByteBuffer.wrap(bytes))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VpDecoderConfigurationRecord.decode(MediaFormat.MIMETYPE_VIDEO_VP8, ByteBuffer.wrap(byteArrayOf(2, 0, 0xA2.toByte(), 2, 2, 2, 0, 0)))
        }
    }

    @Test
    fun truncatedAndInterframeHeadersCannotCreateASequence() {
        for (name in listOf("vp8", "vp9")) {
            val bytes = javaClass.getResourceAsStream("/vpx/$name-keyframe.bin")!!.use { it.readBytes() }
            assertThrows(IllegalArgumentException::class.java) {
                VpDecoderConfigurationRecord.fromKeyFrame("video/x-vnd.on2.$name", ByteBuffer.wrap(bytes.copyOf(4)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VpDecoderConfigurationRecord.fromKeyFrame(MediaFormat.MIMETYPE_VIDEO_VP8, ByteBuffer.wrap(byteArrayOf(1) + ByteArray(12)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VpDecoderConfigurationRecord.fromKeyFrame(MediaFormat.MIMETYPE_VIDEO_VP9, ByteBuffer.wrap(byteArrayOf(0x86.toByte()) + ByteArray(12)))
        }
    }
}
