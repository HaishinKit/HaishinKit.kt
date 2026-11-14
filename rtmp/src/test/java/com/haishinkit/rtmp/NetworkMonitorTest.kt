package com.haishinkit.rtmp

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkMonitorTest {
    @Test
    fun calculatesRatesAcrossUnequalIntervalsAndReportsQueuedBytes() {
        val monitor = NetworkMonitor(previousTimeNanos = 0, previousBytesIn = 100, previousBytesOut = 200)

        assertEquals(
            NetworkMonitorReport(500, 500, 80, 200, 150),
            monitor.sample(timeNanos = 2_000_000_000, bytesIn = 500, bytesOut = 500, queueBytesOut = 80),
        )
        assertEquals(
            NetworkMonitorReport(650, 520, 30, 300, 40),
            monitor.sample(timeNanos = 2_500_000_000, bytesIn = 650, bytesOut = 520, queueBytesOut = 30),
        )
        assertEquals(
            NetworkMonitorReport(650, 520, 0, 0, 0),
            monitor.sample(timeNanos = 3_500_000_000, bytesIn = 650, bytesOut = 520, queueBytesOut = 0),
        )
    }
}
