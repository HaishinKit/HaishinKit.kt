package com.haishinkit.srt

/**
 * Immutable native statistics snapshot. Byte totals include protocol overhead;
 * outgoing totals also include retransmissions. Fields ending in `Total` and
 * the two byte totals are cumulative. Interval counters cover the time since
 * the previous reset. Buffer sizes and RTT describe the sampled state.
 *
 * @property bytesSent Total outgoing bytes reported by libsrt.
 * @property bytesReceived Total incoming bytes reported by libsrt.
 * @property sendBufferBytes Bytes waiting for acknowledgement in the send buffer.
 * @property receiveBufferBytes Bytes queued in the receive buffer.
 * @property roundTripTimeMs Estimated round-trip time in milliseconds.
 * @property sendRateMbps Measured sending rate in megabits per second.
 * @property receiveRateMbps Measured receiving rate in megabits per second.
 * @property msTimeStamp The elapsed time since the socket started, in milliseconds.
 * @property pktSentTotal The total number of sent data packets, including retransmissions.
 * @property pktRecvTotal The total number of received packets.
 * @property pktSndLossTotal The total number of lost packets (sender side).
 * @property pktRcvLossTotal The total number of lost packets (receiver side).
 * @property pktRetransTotal The total number of retransmitted packets.
 * @property pktSentACKTotal The total number of sent ACK packets.
 * @property pktRecvACKTotal The total number of received ACK packets.
 * @property pktSentNAKTotal The total number of sent NAK packets.
 * @property pktRecvNAKTotal The total number of received NAK packets.
 * @property usSndDurationTotal The cumulative time spent sending data, excluding idle time, in microseconds.
 * @property pktSndDropTotal The number of packets dropped because they were too late to send.
 * @property pktRcvDropTotal The number of packets dropped because they were too late to play.
 * @property pktRcvUndecryptTotal The number of packets that could not be decrypted.
 * @property byteRcvLossTotal The total number of lost bytes.
 * @property byteRetransTotal The total number of retransmitted bytes.
 * @property byteSndDropTotal The number of bytes dropped because they were too late to send.
 * @property byteRcvDropTotal The number of bytes dropped because they were too late to play, estimated from the average packet size.
 * @property byteRcvUndecryptTotal The number of bytes that could not be decrypted.
 * @property pktSent The number of sent data packets, including retransmissions.
 * @property pktRecv The number of received packets.
 * @property pktSndLoss The number of lost packets (sender side).
 * @property pktRcvLoss The number of lost packets (receiver side).
 * @property pktRetrans The number of retransmitted packets.
 * @property pktRcvRetrans The number of retransmitted packets received.
 * @property pktSentACK The number of sent ACK packets.
 * @property pktRecvACK The number of received ACK packets.
 * @property pktSentNAK The number of sent NAK packets.
 * @property pktRecvNAK The number of received NAK packets.
 * @property usSndDuration The time spent sending data during the measurement interval, excluding idle time, in microseconds.
 * @property pktReorderDistance The sequence-number distance of reordered received packets.
 * @property pktRcvAvgBelatedTime The average delay of late packets whose sequence numbers precede the acknowledged position.
 * @property pktRcvBelated The number of received packets ignored because they arrived too late.
 * @property pktSndDrop The number of packets dropped because they were too late to send.
 * @property pktRcvDrop The number of packets dropped because they were too late to play.
 * @property pktRcvUndecrypt The number of packets that could not be decrypted.
 * @property byteSent The number of sent data bytes, including retransmissions.
 * @property byteRecv The number of received bytes.
 * @property byteRcvLoss The number of lost bytes reported by the receiver.
 * @property byteRetrans The number of retransmitted bytes.
 * @property byteSndDrop The number of bytes dropped because they were too late to send.
 * @property byteRcvDrop The number of bytes dropped because they were too late to play, estimated from the average packet size.
 * @property byteRcvUndecrypt The number of bytes that could not be decrypted.
 * @property usPktSndPeriod The packet sending period, in microseconds.
 * @property pktFlowWindow The flow window size, in number of packets.
 * @property pktCongestionWindow The congestion window size, in number of packets.
 * @property pktFlightSize The number of packets in flight.
 * @property mbpsBandwidth The estimated available bandwidth, in megabits per second.
 * @property byteAvailSndBuf The available space in the send buffer, in bytes.
 * @property byteAvailRcvBuf The available space in the receive buffer, in bytes.
 * @property mbpsMaxBW The transmission bandwidth limit, in megabits per second.
 * @property byteMSS The maximum transmission unit, in bytes.
 * @property pktSndBuf The number of unacknowledged packets in the send buffer.
 * @property msSndBuf The time span of unacknowledged data in the send buffer, in milliseconds.
 * @property msSndTsbPdDelay The sender’s timestamp-based packet delivery delay, in milliseconds.
 * @property pktRcvBuf The number of packets awaiting delivery in the receive buffer.
 * @property msRcvBuf The time span of data awaiting delivery in the receive buffer, in milliseconds.
 * @property msRcvTsbPdDelay The receiver’s timestamp-based packet delivery delay, in milliseconds.
 * @property pktSndFilterExtraTotal The number of control packets supplied by packet filter.
 * @property pktRcvFilterExtraTotal The number of filter control packets received and consumed by the packet filter.
 * @property pktRcvFilterSupplyTotal The number of packets recovered by the packet filter, such as through forward error correction.
 * @property pktRcvFilterLossTotal The number of lost packets that the packet filter could not recover.
 * @property pktSndFilterExtra The number of control packets supplied by packet filter.
 * @property pktRcvFilterExtra The number of filter control packets received and consumed by the packet filter.
 * @property pktRcvFilterSupply The number of packets recovered by the packet filter, such as through forward error correction.
 * @property pktRcvFilterLoss The number of lost packets that the packet filter could not recover.
 * @property pktReorderTolerance The packet reordering tolerance, measured in packets.
 */
data class SrtPerformanceData(
    val bytesSent: Long,
    val bytesReceived: Long,
    val sendBufferBytes: Int,
    val receiveBufferBytes: Int,
    val roundTripTimeMs: Double,
    val sendRateMbps: Double,
    val receiveRateMbps: Double,
    val msTimeStamp: Long = 0,
    val pktSentTotal: Long = 0,
    val pktRecvTotal: Long = 0,
    val pktSndLossTotal: Int = 0,
    val pktRcvLossTotal: Int = 0,
    val pktRetransTotal: Int = 0,
    val pktSentACKTotal: Int = 0,
    val pktRecvACKTotal: Int = 0,
    val pktSentNAKTotal: Int = 0,
    val pktRecvNAKTotal: Int = 0,
    val usSndDurationTotal: Long = 0,
    val pktSndDropTotal: Int = 0,
    val pktRcvDropTotal: Int = 0,
    val pktRcvUndecryptTotal: Int = 0,
    val byteRcvLossTotal: Long = 0,
    val byteRetransTotal: Long = 0,
    val byteSndDropTotal: Long = 0,
    val byteRcvDropTotal: Long = 0,
    val byteRcvUndecryptTotal: Long = 0,
    val pktSent: Long = 0,
    val pktRecv: Long = 0,
    val pktSndLoss: Int = 0,
    val pktRcvLoss: Int = 0,
    val pktRetrans: Int = 0,
    val pktRcvRetrans: Int = 0,
    val pktSentACK: Int = 0,
    val pktRecvACK: Int = 0,
    val pktSentNAK: Int = 0,
    val pktRecvNAK: Int = 0,
    val usSndDuration: Long = 0,
    val pktReorderDistance: Int = 0,
    val pktRcvAvgBelatedTime: Double = 0.0,
    val pktRcvBelated: Long = 0,
    val pktSndDrop: Int = 0,
    val pktRcvDrop: Int = 0,
    val pktRcvUndecrypt: Int = 0,
    val byteSent: Long = 0,
    val byteRecv: Long = 0,
    val byteRcvLoss: Long = 0,
    val byteRetrans: Long = 0,
    val byteSndDrop: Long = 0,
    val byteRcvDrop: Long = 0,
    val byteRcvUndecrypt: Long = 0,
    val usPktSndPeriod: Double = 0.0,
    val pktFlowWindow: Int = 0,
    val pktCongestionWindow: Int = 0,
    val pktFlightSize: Int = 0,
    val mbpsBandwidth: Double = 0.0,
    val byteAvailSndBuf: Int = 0,
    val byteAvailRcvBuf: Int = 0,
    val mbpsMaxBW: Double = 0.0,
    val byteMSS: Int = 0,
    val pktSndBuf: Int = 0,
    val msSndBuf: Int = 0,
    val msSndTsbPdDelay: Int = 0,
    val pktRcvBuf: Int = 0,
    val msRcvBuf: Int = 0,
    val msRcvTsbPdDelay: Int = 0,
    val pktSndFilterExtraTotal: Int = 0,
    val pktRcvFilterExtraTotal: Int = 0,
    val pktRcvFilterSupplyTotal: Int = 0,
    val pktRcvFilterLossTotal: Int = 0,
    val pktSndFilterExtra: Int = 0,
    val pktRcvFilterExtra: Int = 0,
    val pktRcvFilterSupply: Int = 0,
    val pktRcvFilterLoss: Int = 0,
    val pktReorderTolerance: Int = 0,
) {
    constructor(
        bytesSent: Long,
        bytesReceived: Long,
        sendBufferBytes: Int,
        receiveBufferBytes: Int,
        roundTripTimeMs: Double,
        sendRateMbps: Double,
        receiveRateMbps: Double,
    ) : this(
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        sendBufferBytes = sendBufferBytes,
        receiveBufferBytes = receiveBufferBytes,
        roundTripTimeMs = roundTripTimeMs,
        sendRateMbps = sendRateMbps,
        receiveRateMbps = receiveRateMbps,
        msTimeStamp = 0,
    )
}
