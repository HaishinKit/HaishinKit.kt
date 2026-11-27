package com.haishinkit.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VpVideoTest {
    @Test fun vp8KeyframeDimensionsAndInterframes() {
        val data = hex("1000009d012a80006000")
        assertEquals(128 to 96, VpVideo.dimensions(RtcVideoCodec.VP8, data))
        assertFalse(VpVideo.keyframe(RtcVideoCodec.VP8, byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { VpVideo.dimensions(RtcVideoCodec.VP8, data.copyOf(9)) }
    }

    @Test fun vp9ProfileZeroDimensionsAndMalformedHeaders() {
        // Frame marker/profile0/key/show_frame, sync, color0/range0, 128x96.
        val bits = "10000010" + "010010011000001101000010" + "0000" + 127.toString(2).padStart(16, '0') + 95.toString(2).padStart(16, '0')
        val frame = bits.padEnd((bits.length + 7) / 8 * 8, '0').chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        assertEquals(128 to 96, VpVideo.dimensions(RtcVideoCodec.VP9, frame))
        assertFalse(VpVideo.keyframe(RtcVideoCodec.VP9, byteArrayOf(0x86.toByte())))
        assertFalse(VpVideo.keyframe(RtcVideoCodec.VP9, byteArrayOf(0x88.toByte())))
        assertThrows(IllegalArgumentException::class.java) { VpVideo.dimensions(RtcVideoCodec.VP9, frame.copyOf(5)) }
        assertThrows(IllegalArgumentException::class.java) { VpVideo.keyframe(RtcVideoCodec.VP9, byteArrayOf(0xa2.toByte())) }
    }

    @Test fun negotiationRequiresOfferedCodecAndVp9ProfileZero() {
        for (codec in listOf(RtcVideoCodec.VP8, RtcVideoCodec.VP9)) {
            val answer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:video\r\na=sendonly\r\na=rtpmap:96 ${codec.rtpName}/90000\r\n"
            assertEquals(setOf("video"), Sdp.validateAnswer(answer, false, false, true, videoCodec = codec))
            assertThrows(IllegalArgumentException::class.java) { Sdp.validateAnswer(answer, false, false, true) }
            assertThrows(IllegalArgumentException::class.java) { Sdp.validateAnswer(answer.replace("96", "97"), false, false, true, videoCodec = codec) }
            if (codec == RtcVideoCodec.VP9) {
                assertEquals(setOf("video"), Sdp.validateAnswer(answer + "a=fmtp:96 profile-id=0\r\n", false, false, true, videoCodec = codec))
                assertThrows(IllegalArgumentException::class.java) { Sdp.validateAnswer(answer + "a=fmtp:96 profile-id=2\r\n", false, false, true, videoCodec = codec) }
            }
        }
    }

    @Test fun vp9ReferenceAndDisplayPicturesGetDistinctDecoderTimes() {
        assertEquals(100L, VpVideo.decoderTime(RtcVideoCodec.VP9, 100, 99, 99))
        assertEquals(101L, VpVideo.decoderTime(RtcVideoCodec.VP9, 100, 100, 100))
        assertEquals(102L, VpVideo.decoderTime(RtcVideoCodec.VP9, 101, 100, 101))
        assertEquals(null, VpVideo.decoderTime(RtcVideoCodec.VP9, 99, 100, 101))
        assertEquals(null, VpVideo.decoderTime(RtcVideoCodec.VP8, 100, 100, 100))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
