package com.haishinkit.rtmp

import com.haishinkit.stream.StreamSession.ReadyState

/** Only lifecycle statuses change a session; buffer/format notifications do not. */
internal object RtmpSessionStatus {
    private val terminal =
        setOf(
            RtmpConnection.Code.CONNECT_CLOSED.rawValue,
            RtmpConnection.Code.CONNECT_REJECTED.rawValue,
            RtmpConnection.Code.CONNECT_APP_SHUTDOWN.rawValue,
            RtmpConnection.Code.CONNECT_IDLE_TIME_OUT.rawValue,
            RtmpConnection.Code.CONNECT_NETWORK_CHANGE.rawValue,
            RtmpStream.Code.PLAY_STOP.rawValue,
            RtmpStream.Code.PLAY_NO_SUPPORTED_TRACK_FOUND.rawValue,
            RtmpStream.Code.UNPUBLISH_SUCCESS.rawValue,
        )

    fun nextState(
        current: ReadyState,
        code: String?,
        level: String?,
    ): ReadyState {
        if (current == ReadyState.CLOSED || current == ReadyState.CLOSING) return current
        if (level == "error" || code in terminal) return ReadyState.CLOSED
        if (code == RtmpStream.Code.PLAY_START.rawValue || code == RtmpStream.Code.PUBLISH_START.rawValue) return ReadyState.OPEN
        return current
    }
}
