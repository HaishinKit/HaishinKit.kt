package com.haishinkit.rtmp.message

import android.media.MediaFormat
import com.haishinkit.codec.AudioCodec
import com.haishinkit.rtmp.RtmpMuxer
import com.haishinkit.rtmp.iso.OpusConfiguration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class RtmpOpusMessageTest {
    @Test fun opusHeadersAndCodedPacketsRoundTripWithoutMutatingInput() {
        for (packet in listOf(0, 1, 2)) {
            val bytes = if (packet == 0) header() else byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
            val source = ByteBuffer.wrap(byteArrayOf(0) + bytes).apply { position(1) }
            val message =
                RtmpAudioMessage().apply {
                    codec = RtmpMuxer.FLV_AUDIO_CODEC_EX_HEADER
                    aacPacketType = packet.toByte()
                    fourCC = RtmpMuxer.FLV_AUDIO_FOUR_CC_OPUS
                    data = source
                }
            val wire = ByteBuffer.allocate(message.length)
            message.encode(wire)
            assertEquals(1, source.position())
            wire.flip()
            assertEquals(0x90 or packet, wire.get(0).toInt() and 255)
            assertEquals(RtmpMuxer.FLV_AUDIO_FOUR_CC_OPUS, wire.getInt(1))
            val decoded = RtmpAudioMessage().apply { length = wire.remaining() }.decode(wire) as RtmpAudioMessage
            assertEquals(packet.toByte(), decoded.aacPacketType)
            assertArrayEquals(bytes, decoded.data!!.array())
        }
    }

    @Test fun decoderConfigurationUsesNativeCsdAndPreservesGain() {
        val bytes =
            header().apply {
                this[10] = 0x38
                this[11] = 1
                this[16] = 0
                this[17] = 6
            }
        val configuration = OpusConfiguration.decode(ByteBuffer.wrap(bytes))!!
        assertEquals(312, configuration.preSkip)
        val codec = AudioCodec()
        configuration.apply(codec)
        assertEquals(48000, codec.sampleRate)
        assertEquals(2, codec.channelCount)
        val csd = codec.options.associate { it.key to (it.value as ByteBuffer).duplicate() }
        assertArrayEquals(bytes, csd.getValue("csd-0").array())
        assertEquals(6_500_000L, csd.getValue("csd-1").order(ByteOrder.nativeOrder()).long)
        assertEquals(80_000_000L, csd.getValue("csd-2").order(ByteOrder.nativeOrder()).long)
    }

    @Test fun malformedHeadersAreRejectedAndCannotReuseOldPayload() {
        val valid = header()
        for (size in 0 until 19) assertNull(OpusConfiguration.decode(ByteBuffer.wrap(valid.copyOf(size))))
        for ((offset, value) in listOf(0 to 0, 8 to 16, 9 to 3, 18 to 1)) {
            assertNull(OpusConfiguration.decode(ByteBuffer.wrap(valid.copyOf().apply { this[offset] = value.toByte() })))
        }
        val message = RtmpAudioMessage().apply { data = ByteBuffer.wrap(valid) }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0x90.toByte()), byteArrayOf(0x90.toByte(), 0x4f, 0x70))) {
            message.length = bytes.size
            val wire = ByteBuffer.wrap(bytes)
            message.decode(wire)
            assertNull(message.data)
            assertEquals(bytes.size, wire.position())
        }
    }

    @Test fun aacPayloadStillRoundTrips() {
        val message =
            RtmpAudioMessage().apply {
                codec = RtmpMuxer.FLV_AUDIO_CODEC_AAC
                aacPacketType = 1
                data = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
            }
        val wire = ByteBuffer.allocate(message.length)
        message.encode(wire)
        assertArrayEquals(byteArrayOf(0xaf.toByte(), 1, 1, 2, 3), wire.array())
        wire.flip()
        val decoded = RtmpAudioMessage().apply { length = wire.remaining() }.decode(wire) as RtmpAudioMessage
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.data!!.array())
    }

    @Test fun encoderMimeSettingIsExplicitAndValidatesUnsupportedFormats() {
        val codec = AudioCodec()
        val settings = AudioCodec.Setting(codec)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, settings.mimeType)
        settings.mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS
        assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, codec.outputMimeType)
        assertThrows(IllegalArgumentException::class.java) { settings.mimeType = MediaFormat.MIMETYPE_VIDEO_AVC }
    }

    private fun header() =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)
            put(2)
            putShort(0)
            putInt(44100)
            putShort(0)
            put(0)
        }.array()
}
