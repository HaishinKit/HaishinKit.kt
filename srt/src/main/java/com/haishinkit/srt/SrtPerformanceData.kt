package com.haishinkit.srt

/**
 * Immutable native statistics snapshot. Byte totals include protocol overhead;
 * outgoing totals also include retransmissions. Reading does not reset counters.
 *
 * @property bytesSent Total outgoing bytes reported by libsrt.
 * @property bytesReceived Total incoming bytes reported by libsrt.
 * @property sendBufferBytes Bytes waiting for acknowledgement in the send buffer.
 * @property receiveBufferBytes Bytes queued in the receive buffer.
 * @property roundTripTimeMs Estimated round-trip time in milliseconds.
 * @property sendRateMbps Measured sending rate in megabits per second.
 * @property receiveRateMbps Measured receiving rate in megabits per second.
 */
data class SrtPerformanceData(
    val bytesSent: Long,
    val bytesReceived: Long,
    val sendBufferBytes: Int,
    val receiveBufferBytes: Int,
    val roundTripTimeMs: Double,
    val sendRateMbps: Double,
    val receiveRateMbps: Double,
)
