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
            assertEquals(setOf("audio", "video"), SDP.validateAnswer(answer, true, true, true))
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
