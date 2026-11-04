package com.haishinkit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CodecOutputBufferTest {
    @Test
    fun oldDecoderOutputIsNeitherReadNorReleasedOnReplacement() {
        val guard = Any()
        var generation = 1
        var reads = 0
        var releases = 0
        val output = CodecOutputBuffer(guard, { generation == 1 }) { _, _ -> releases++ }
        synchronized(guard) { generation = 2 }
        assertFalse(output.process { reads++ })
        output.release(true, 123)
        assertEquals(0, reads)
        assertEquals(0, releases)
    }

    @Test
    fun decoderReleaseWaitsUntilInFlightAudioAccessFinishes() {
        val guard = Any()
        var current = true
        val entered = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val replacing = CountDownLatch(1)
        val output = CodecOutputBuffer(guard, { current }) { _, _ -> error("Old decoder was released after replacement") }
        val threads = Executors.newFixedThreadPool(2)
        try {
            val reading =
                threads.submit<Boolean> {
                    output.process {
                        entered.countDown()
                        assertTrue(finishRead.await(3, TimeUnit.SECONDS))
                        assertTrue(current)
                    }
                }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val replacement =
                threads.submit {
                    replacing.countDown()
                    synchronized(guard) { current = false }
                }
            assertTrue(replacing.await(3, TimeUnit.SECONDS))
            assertFalse(replacement.isDone)
            finishRead.countDown()
            assertTrue(reading.get(3, TimeUnit.SECONDS))
            replacement.get(3, TimeUnit.SECONDS)
            output.release(false, 0)
        } finally {
            finishRead.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun outputIsReleasedOnceWithItsOriginalRenderTimestamp() {
        var releases = 0
        val output =
            CodecOutputBuffer(Any(), { true }) { render, time ->
                assertTrue(render)
                assertEquals(123L, time)
                releases++
            }
        output.release(true, 123)
        output.release(false, 456)
        assertFalse(output.process { error("Released buffer was accessed") })
        assertEquals(1, releases)
    }
}
