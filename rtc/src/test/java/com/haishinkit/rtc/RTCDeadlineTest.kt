package com.haishinkit.rtc

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RTCDeadlineTest {
    @Test fun connectionDeadlineBecomesAnIoFailure() {
        val failure =
            assertThrows(IOException::class.java) {
                runBlocking { withRTCDeadline(10, "connection timed out") { awaitCancellation() } }
            }
        assertEquals("connection timed out", failure.message)
    }

    @Test fun aSurfaceDeadlineFailsItsParentInsteadOfSilentlyCancellingTheDecoder() {
        assertThrows(IOException::class.java) {
            runBlocking {
                coroutineScope {
                    launch { withRTCDeadline(10, "surface missing") { awaitCancellation() } }
                    awaitCancellation()
                }
            }
        }
    }

    @Test fun callerTimeoutRemainsCancellation() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(10) { withRTCDeadline(10_000, "not this deadline") { awaitCancellation() } }
            }
        }
    }
}
