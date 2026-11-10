package com.haishinkit.rtc

/** Set before creating a session. ICE URLs accept libdatachannel's STUN/TURN URI syntax. */
data class RTCConfiguration(
    val iceServers: List<String> = emptyList(),
    val bearerToken: String? = null,
    val connectionTimeoutMillis: Long = 30_000,
    /** Connect/read timeout and absolute deadline shared by one HTTP operation's redirects/body. */
    val httpTimeoutMillis: Int = 10_000,
) {
    init {
        require(connectionTimeoutMillis > 0 && httpTimeoutMillis > 0)
        require(bearerToken == null || (bearerToken.isNotBlank() && '\r' !in bearerToken && '\n' !in bearerToken))
    }
}
