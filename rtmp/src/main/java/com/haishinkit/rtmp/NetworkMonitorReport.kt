package com.haishinkit.rtmp

/**
 * Transport statistics for a single RTMP or RTMPS connection.
 *
 * @property totalBytesIn Cumulative bytes read from the transport.
 * @property totalBytesOut Cumulative bytes written to the transport.
 * @property currentQueueBytesOut Bytes awaiting write completion, including any write in progress.
 * @property currentBytesInPerSecond Average receive rate over the latest sampling interval.
 * @property currentBytesOutPerSecond Average send rate over the latest sampling interval.
 */
data class NetworkMonitorReport(
    val totalBytesIn: Long,
    val totalBytesOut: Long,
    val currentQueueBytesOut: Long,
    val currentBytesInPerSecond: Long,
    val currentBytesOutPerSecond: Long,
)
