package com.haishinkit.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpLimitsTest {
    @Test fun receiverEnvelopeUsesConditionalRateAndBothAxes() {
        val result = VpLimits.select(64, 4096, 64, 4096) { w, h, rate -> w.toLong() * h * rate <= 1280L * 720 * 30 }
        assertEquals(VpLimits(3600, 30), result)
        val limitedAxis =
            VpLimits.select(64, 4096, 64, 720) { _, h, _ ->
                assertTrue(h <= 720)
                true
            }
        assertTrue(kotlin.math.sqrt(8.0 * limitedAxis.maxFs).toInt() * 16 <= 720)
        assertThrows(IllegalStateException::class.java) { VpLimits.select(64, 4096, 64, 4096) { _, _, _ -> false } }
    }

    @Test fun incomingDimensionsAreCheckedBeforeDecoderReplacement() {
        val limits = VpLimits(3600, 30)
        limits.validateDimensions(1280, 720)
        assertThrows(IllegalArgumentException::class.java) { limits.validateDimensions(2048, 2048) }
        assertThrows(IllegalArgumentException::class.java) { limits.validateDimensions(4096, 16) }
    }

    @Test fun senderRejectsExplicitReceiverLimitsAndToleratesAbsentFields() {
        fun answer(extra: String) = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:video\r\na=recvonly\r\na=rtpmap:96 VP8/90000\r\na=fmtp:96 $extra\r\n"
        Sdp.validateVpSender(answer(""), 1280, 720, 30)
        Sdp.validateVpSender(answer("max-fs=3600;max-fr=30"), 1280, 720, 30)
        assertThrows(IllegalArgumentException::class.java) { Sdp.validateVpSender(answer("max-fs=360;max-fr=30"), 1280, 720, 30) }
        assertThrows(IllegalArgumentException::class.java) { Sdp.validateVpSender(answer("max-fs=3600;max-fr=15"), 1280, 720, 30) }
        // Area fits but a very long thin frame exceeds the RFC per-axis bound.
        assertThrows(IllegalArgumentException::class.java) { Sdp.validateVpSender(answer("max-fs=1200"), 1600, 16, 15) }
        for (value in listOf("0", "-1", "garbage", "999999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { Sdp.validateVpSender(answer("max-fr=$value"), 128, 96, 15) }
        }
    }
}
