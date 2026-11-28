package com.haishinkit.media.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InputAudioProcessorTest {
    private val mono = PcmAudioFormat(48000, 1)

    @Test fun unprocessedTracksRetainTheOriginalBuffer() {
        val pipeline = InputAudioProcessor(null)
        val input = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)).apply { position(2) }
        pipeline.append(input, mono) { assertSame(input, it) }
        assertEquals(2, input.position())
        pipeline.close()
    }

    @Test fun eachTrackOwnsItsStateAndFormatChangesCloseTheOldState() {
        val created = mutableListOf<Fake>()
        val factory = AudioProcessor.Factory { Fake(it).also(created::add) }
        val first = InputAudioProcessor(factory)
        val second = InputAudioProcessor(factory)
        val input = ByteBuffer.allocate(8)
        first.append(input, mono) {}
        second.append(input, mono) {}
        first.reset()
        first.append(input, PcmAudioFormat(48000, 2)) {}
        assertEquals(3, created.size)
        assertEquals(1, created[0].resets)
        assertEquals(1, created[0].closes)
        assertEquals(0, created[1].closes)
        first.close()
        second.close()
        first.close()
        assertTrue(created.all { it.closes == 1 })
        first.append(input, mono) { fail("Closed pipeline must not emit audio") }
    }

    @Test fun aFactoryCannotSilentlyChangeTheTrackFormat() {
        val wrong = Fake(PcmAudioFormat(44100, 1))
        val pipeline = InputAudioProcessor { wrong }
        assertThrows(IllegalArgumentException::class.java) { pipeline.append(ByteBuffer.allocate(2), mono) {} }
        assertEquals(1, wrong.closes)
        pipeline.close()
        assertEquals(1, wrong.closes)
    }

    @Test fun incompleteOutputFramesAreRejected() {
        val pipeline =
            InputAudioProcessor { format ->
                object : AudioProcessor {
                    override val format = format

                    override fun process(input: ByteBuffer) = ByteBuffer.allocate(1)

                    override fun reset() {}

                    override fun close() {}
                }
            }
        assertThrows(IllegalArgumentException::class.java) { pipeline.append(ByteBuffer.allocate(2), mono) {} }
        pipeline.close()
    }

    @Test fun closeWaitsForAnInFlightPushCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val pipeline =
            InputAudioProcessor { format ->
                object : AudioProcessor {
                    override val format = format

                    override fun process(input: ByteBuffer): ByteBuffer {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        assertEquals(1L, closed.count)
                        return input
                    }

                    override fun reset() {}

                    override fun close() {
                        closed.countDown()
                    }
                }
            }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val processing = executor.submit { pipeline.append(ByteBuffer.allocate(2), mono) {} }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = executor.submit { pipeline.close() }
            assertFalse(closed.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            processing.get(5, TimeUnit.SECONDS)
            closing.get(5, TimeUnit.SECONDS)
            assertEquals(0L, closed.count)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private class Fake(override val format: PcmAudioFormat) : AudioProcessor {
        var resets = 0
        var closes = 0

        override fun process(input: ByteBuffer) = input

        override fun reset() {
            resets++
        }

        override fun close() {
            closes++
        }
    }
}
