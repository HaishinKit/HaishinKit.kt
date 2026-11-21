package com.haishinkit.rtmp

internal class NetworkMonitor(
    private var previousTimeNanos: Long,
    private var previousBytesIn: Long,
    private var previousBytesOut: Long,
) {
    fun sample(
        timeNanos: Long,
        bytesIn: Long,
        bytesOut: Long,
        queueBytesOut: Long,
    ): NetworkMonitorReport? {
        val elapsedSeconds = (timeNanos - previousTimeNanos) / 1_000_000_000.0
        if (elapsedSeconds <= 0) return null

        val report =
            NetworkMonitorReport(
                totalBytesIn = bytesIn,
                totalBytesOut = bytesOut,
                currentQueueBytesOut = queueBytesOut,
                currentBytesInPerSecond = ((bytesIn - previousBytesIn) / elapsedSeconds).toLong(),
                currentBytesOutPerSecond = ((bytesOut - previousBytesOut) / elapsedSeconds).toLong(),
            )
        previousTimeNanos = timeNanos
        previousBytesIn = bytesIn
        previousBytesOut = bytesOut
        return report
    }
}
