package com.haishinkit.rtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NativePeerTest {
    @Test fun duplexOpusAndUnicodeDataChannelRoundTrip() {
        assumeTrue("Enable native integration tests", System.getProperty("haishinkit.rtc.nativeTests") == "true" || System.getProperty("java.vm.name") == "Dalvik")
        val offers = LinkedBlockingQueue<String>()
        val answers = LinkedBlockingQueue<String>()
        val ready = CountDownLatch(4)
        val leftAudio = LinkedBlockingQueue<ByteArray>()
        val rightAudio = LinkedBlockingQueue<ByteArray>()
        val leftText = LinkedBlockingQueue<String>()
        val rightText = LinkedBlockingQueue<String>()
        val failures = LinkedBlockingQueue<String>()

        fun event(
            sdp: LinkedBlockingQueue<String>,
            messages: LinkedBlockingQueue<String>,
        ): (Int, Int, String) -> Unit = { kind, _, text ->
            when (kind) {
                NativePeer.SDP -> sdp.offer(text)
                NativePeer.TRACK_OPEN, NativePeer.DATA_OPEN -> ready.countDown()
                NativePeer.DATA_TEXT -> messages.offer(text)
                NativePeer.ERROR -> failures.offer(text)
            }
        }
        val left = NativePeer(true, true, false, emptyArray(), event(offers, leftText), { _, data, _ -> leftAudio.offer(data) }, true, "test-events")
        val right = NativePeer(true, true, false, emptyArray(), event(answers, rightText), { _, data, _ -> rightAudio.offer(data) }, true)
        try {
            left.offer()
            right.acceptOffer(requireNotNull(offers.poll(10, TimeUnit.SECONDS)))
            val answer = requireNotNull(answers.poll(10, TimeUnit.SECONDS))
            assertEquals(setOf("audio"), Sdp.validateAnswer(answer, false, true, false, duplex = true))
            assertThrows(IllegalArgumentException::class.java) { Sdp.validateAnswer(answer, true, true, false) }
            left.answer(answer)
            assertTrue("Tracks/data channel did not open: $failures", ready.await(15, TimeUnit.SECONDS))
            val opus = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
            left.send(true, opus, 1_000_000)
            right.send(true, opus, 1_000_000)
            assertArrayEquals(opus, leftAudio.poll(5, TimeUnit.SECONDS))
            assertArrayEquals(opus, rightAudio.poll(5, TimeUnit.SECONDS))
            val message = "日本語の会話 🎙️" + 0.toChar() + "끝"
            left.sendText(message)
            right.sendText(message)
            assertEquals(message, leftText.poll(5, TimeUnit.SECONDS))
            assertEquals(message, rightText.poll(5, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            left.close()
            right.close()
        }
        assertThrows(IllegalStateException::class.java) { left.sendText("closed") }
    }

    @Test fun vp8RoundTripOverIceDtlsSrtp() = vpRoundTrip(RtcVideoCodec.VP8)

    @Test fun vp9RoundTripOverIceDtlsSrtp() = vpRoundTrip(RtcVideoCodec.VP9)

    private fun vpRoundTrip(codec: RtcVideoCodec) {
        assumeTrue("Set -PrtcNativeLibraryDir to run native integration tests", System.getProperty("haishinkit.rtc.nativeTests") == "true" || System.getProperty("java.vm.name") == "Dalvik")
        val offers = LinkedBlockingQueue<String>()
        val answers = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(4)
        val pli = CountDownLatch(1)
        val failures = LinkedBlockingQueue<String>()
        val audioFrames = LinkedBlockingQueue<ByteArray>()
        val videoFrames = LinkedBlockingQueue<ByteArray>()
        val sender =
            NativePeer(true, true, true, emptyArray(), { kind, _, text ->
                when (kind) {
                    NativePeer.SDP -> offers.offer(text)
                    NativePeer.TRACK_OPEN -> opened.countDown()
                    NativePeer.KEYFRAME -> pli.countDown()
                    NativePeer.ERROR -> failures.offer(text)
                }
            }, { _, _, _ -> }, videoCodec = codec)
        val receiver =
            NativePeer(false, true, true, emptyArray(), { kind, _, text ->
                when (kind) {
                    NativePeer.SDP -> answers.offer(text)
                    NativePeer.TRACK_OPEN -> opened.countDown()
                    NativePeer.ERROR -> failures.offer(text)
                }
            }, { audio, data, _ -> (if (audio) audioFrames else videoFrames).offer(data) }, videoCodec = codec)
        try {
            sender.offer()
            val offer = requireNotNull(offers.poll(10, TimeUnit.SECONDS)) { "ICE offer gathering timed out" }
            receiver.acceptOffer(offer)
            val answer = requireNotNull(answers.poll(10, TimeUnit.SECONDS)) { "ICE answer gathering timed out" }
            assertEquals(setOf("audio", "video"), Sdp.validateAnswer(answer, true, true, true, videoCodec = codec))
            assertTrue("VP receiver limits are missing: $answer", answer.contains("max-fs=3600") && answer.contains("max-fr=30"))
            sender.answer(answer)
            assertTrue("Tracks did not open: $failures", opened.await(15, TimeUnit.SECONDS))
            val opus = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
            val frame = byteArrayOf(if (codec == RtcVideoCodec.VP9) 0x82.toByte() else 0x10) + ByteArray(5000) { 0x55 }
            sender.send(true, opus, 1_000_000)
            sender.send(false, frame, 1_000_000)
            assertArrayEquals(opus, audioFrames.poll(5, TimeUnit.SECONDS))
            assertArrayEquals(frame, videoFrames.poll(5, TimeUnit.SECONDS))
            if (codec == RtcVideoCodec.VP9) {
                val hidden = frame.copyOf().apply { this[0] = 0x84.toByte() }
                val visible = frame.copyOf().apply { this[0] = 0x86.toByte() }
                sender.send(false, hidden, 2_000_000)
                sender.send(false, visible, 2_000_000)
                assertArrayEquals(hidden, videoFrames.poll(5, TimeUnit.SECONDS))
                assertArrayEquals(visible, videoFrames.poll(5, TimeUnit.SECONDS))
            }
            receiver.requestKeyframe()
            assertTrue("RTCP PLI was not delivered", pli.await(5, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            sender.close()
            receiver.close()
            sender.close()
            receiver.close()
        }
        assertThrows(IllegalStateException::class.java) { sender.offer() }
    }

    @Test fun h264AndOpusRoundTripOverIceDtlsSrtp() {
        assumeTrue("Set -PrtcNativeLibraryDir to run native integration tests", System.getProperty("haishinkit.rtc.nativeTests") == "true" || System.getProperty("java.vm.name") == "Dalvik")
        val offers = LinkedBlockingQueue<String>()
        val answers = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(4)
        val pli = CountDownLatch(1)
        val failures = LinkedBlockingQueue<String>()
        val audioFrames = LinkedBlockingQueue<ByteArray>()
        val videoFrames = LinkedBlockingQueue<ByteArray>()
        val sender =
            NativePeer(true, true, true, emptyArray(), { kind, _, text ->
                when (kind) {
                    NativePeer.SDP -> offers.offer(text)
                    NativePeer.TRACK_OPEN -> opened.countDown()
                    NativePeer.KEYFRAME -> pli.countDown()
                    NativePeer.ERROR -> failures.offer(text)
                }
            }, { _, _, _ -> })
        val receiver =
            NativePeer(false, true, true, emptyArray(), { kind, _, text ->
                when (kind) {
                    NativePeer.SDP -> answers.offer(text)
                    NativePeer.TRACK_OPEN -> opened.countDown()
                    NativePeer.ERROR -> failures.offer(text)
                }
            }, { audio, data, _ -> (if (audio) audioFrames else videoFrames).offer(data) })
        try {
            sender.offer()
            val offer = requireNotNull(offers.poll(10, TimeUnit.SECONDS)) { "ICE offer gathering timed out" }
            receiver.acceptOffer(offer)
            val answer = requireNotNull(answers.poll(10, TimeUnit.SECONDS)) { "ICE answer gathering timed out" }
            assertEquals(setOf("audio", "video"), Sdp.validateAnswer(answer, true, true, true))
            sender.answer(answer)
            assertTrue("Tracks did not open: $failures", opened.await(15, TimeUnit.SECONDS))
            val opus = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
            // Include a large IDR NAL to exercise FU-A fragmentation/reassembly.
            val units = listOf(byteArrayOf(0x67, 0x42, 0, 0x1f), byteArrayOf(0x68, 1, 2), byteArrayOf(0x65) + ByteArray(5000) { 0x55 })
            sender.send(true, opus, 1_000_000)
            sender.send(false, H264.annexB(units), 1_000_000)
            assertArrayEquals(opus, audioFrames.poll(5, TimeUnit.SECONDS))
            val decoded = requireNotNull(videoFrames.poll(5, TimeUnit.SECONDS))
            val actualUnits = H264.units(decoded)
            assertEquals(units.size, actualUnits.size)
            units.indices.forEach { assertArrayEquals(units[it], actualUnits[it]) }
            receiver.requestKeyframe()
            assertTrue("RTCP PLI was not delivered", pli.await(5, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            sender.close()
            receiver.close()
            sender.close()
            receiver.close()
        }
        assertThrows(IllegalStateException::class.java) { sender.offer() }
    }
}
