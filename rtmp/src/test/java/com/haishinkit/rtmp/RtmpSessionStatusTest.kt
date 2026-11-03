package com.haishinkit.rtmp

import com.haishinkit.stream.StreamSession.ReadyState
import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpSessionStatusTest {
    @Test
    fun normalPlaybackNotificationsPreserveTheActiveSessionForStopControls() {
        var state = ReadyState.CONNECTING
        state = RtmpSessionStatus.nextState(state, RtmpConnection.Code.CONNECT_SUCCESS.rawValue, "status")
        assertEquals(ReadyState.CONNECTING, state)
        state = RtmpSessionStatus.nextState(state, RtmpStream.Code.BUFFER_FULL.rawValue, "status")
        assertEquals(ReadyState.CONNECTING, state)
        state = RtmpSessionStatus.nextState(state, RtmpStream.Code.PLAY_START.rawValue, "status")
        for (code in listOf(RtmpStream.Code.BUFFER_FULL, RtmpStream.Code.BUFFER_EMPTY, RtmpStream.Code.VIDEO_DIMENSION_CHANGE)) {
            state = RtmpSessionStatus.nextState(state, code.rawValue, "status")
            assertEquals(ReadyState.OPEN, state)
        }
        assertEquals(ReadyState.CLOSED, RtmpSessionStatus.nextState(state, RtmpConnection.Code.CONNECT_CLOSED.rawValue, "status"))
    }

    @Test
    fun failuresCloseAndLateStartCannotReopenAClosedOrClosingSession() {
        assertEquals(ReadyState.OPEN, RtmpSessionStatus.nextState(ReadyState.CONNECTING, RtmpStream.Code.PUBLISH_START.rawValue, "status"))
        assertEquals(ReadyState.CLOSED, RtmpSessionStatus.nextState(ReadyState.OPEN, RtmpStream.Code.PLAY_FAILED.rawValue, "error"))
        assertEquals(ReadyState.CLOSED, RtmpSessionStatus.nextState(ReadyState.CONNECTING, RtmpStream.Code.PLAY_NO_SUPPORTED_TRACK_FOUND.rawValue, "status"))
        for (state in listOf(ReadyState.CLOSED, ReadyState.CLOSING)) {
            assertEquals(state, RtmpSessionStatus.nextState(state, RtmpStream.Code.PLAY_START.rawValue, "status"))
        }
    }
}
