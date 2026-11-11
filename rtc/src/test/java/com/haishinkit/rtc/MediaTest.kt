package com.haishinkit.rtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaTest {
    @Test fun rtpClockUnwrapsAndDoesNotMoveAnchorForReorderedPackets() {
        val clock = RTPClock(48_000)
        assertEquals(20_000L, clock.timeUs(0xfffffff0, 20_000))
        assertEquals(40_000L, clock.timeUs(944, 99_000))
        assertEquals(30_000L, clock.timeUs(464, 200_000))
        assertEquals(60_000L, clock.timeUs(1904, 300_000))
    }

    @Test fun h264FramingPreservesNalUnitsAndParsesSps() {
        val units = listOf(hex("6742000af841a2"), hex("68ce3c80"), hex("658884"))
        val decoded = H264.units(H264.annexB(units))
        units.indices.forEach { assertArrayEquals(units[it], decoded[it]) }
        assertEquals(128 to 96, H264.dimensions(units[0]))
        assertThrows(IllegalArgumentException::class.java) { H264.dimensions(byteArrayOf(0x67, 0, 0, 0)) }
    }

    @Test fun answerRejectsWrongCodecPayloadAndDirection() {
        val answer = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:audio\r\na=recvonly\r\na=rtpmap:111 opus/48000/2\r\n"
        assertEquals(setOf("audio"), SDP.validateAnswer(answer, true, true, false))
        assertThrows(IllegalArgumentException::class.java) { SDP.validateAnswer(answer.replace("opus", "AAC"), true, true, false) }
        assertThrows(IllegalArgumentException::class.java) { SDP.validateAnswer(answer.replace("recvonly", "sendonly"), true, true, false) }
        assertThrows(IllegalArgumentException::class.java) { SDP.validateAnswer(answer.replace("111", "112"), true, true, false) }
        assertThrows(IllegalArgumentException::class.java) { SDP.validateAnswer(answer.replace("audio 9", "audio 0"), true, true, false) }
    }

    @Test fun sessionLevelDirectionAndSdpParameterSetsAreAccepted() {
        val sps = hex("6742000af841a2")
        val pps = hex("68ce3c80")
        val parameter = listOf(sps, pps).joinToString(",") { java.util.Base64.getEncoder().encodeToString(it) }
        val answer = "v=0\r\na=sendonly\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:video\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 profile-level-id=42e01f;packetization-mode=1;sprop-parameter-sets=$parameter\r\n"
        assertEquals(setOf("video"), SDP.validateAnswer(answer, false, false, true))
        val configuration = SDP.videoConfiguration(answer)
        assertArrayEquals(sps, configuration[0])
        assertArrayEquals(pps, configuration[1])
        assertThrows(IllegalArgumentException::class.java) { SDP.videoConfiguration(answer.replace(parameter, "invalid!")) }
    }

    @Test fun rejectsIncompatibleH264ReceiveCapabilities() {
        fun answer(
            profile: String,
            extra: String = "",
        ): String = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:video\r\na=recvonly\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 profile-level-id=$profile;packetization-mode=1;level-asymmetry-allowed=1$extra\r\n"
        assertEquals(setOf("video"), SDP.validateAnswer(answer("42e01f"), true, false, true))
        assertEquals(setOf("video"), SDP.validateAnswer(answer("4d801f"), true, false, true))
        assertThrows(IllegalArgumentException::class.java) { SDP.validateAnswer(answer("42e00c"), true, false, true) }
        assertThrows(IllegalArgumentException::class.java) { SDP.validateAnswer(answer("64001f"), true, false, true) }
        assertThrows(IllegalArgumentException::class.java) { SDP.validateAnswer(answer("42e01f").replace("profile-level-id=42e01f;", ""), true, false, true) }
        assertEquals(setOf("video"), SDP.validateAnswer(answer("42e00c", ";max-recv-level=e01f"), true, false, true))
        assertThrows(IllegalArgumentException::class.java) { SDP.validateAnswer(answer("42e028").replace("recvonly", "sendonly"), false, false, true) }
    }

    @Test fun rejectsEncoderSettingsAboveTheAdvertisedH264Level() {
        SDP.validateH264Sender(1280, 720, 30, 14_000_000)
        SDP.validateH264Sender(854, 480, 30, 640_000)
        assertThrows(IllegalArgumentException::class.java) { SDP.validateH264Sender(1920, 1080, 30, 4_000_000) }
        assertThrows(IllegalArgumentException::class.java) { SDP.validateH264Sender(1280, 720, 60, 4_000_000) }
        assertThrows(IllegalArgumentException::class.java) { SDP.validateH264Sender(1280, 720, 30, 20_000_000) }
        assertThrows(IllegalArgumentException::class.java) { SDP.validateH264Sender(32_000, 16, 1, 640_000) }
    }

    private fun hex(
        value: String,
    ): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
