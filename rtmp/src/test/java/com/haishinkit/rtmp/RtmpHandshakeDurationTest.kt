package com.haishinkit.rtmp

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class RtmpHandshakeDurationTest {
    @Test
    fun durationIsAvailableOnlyAfterReceivingFullS2() {
        val connection = RtmpConnection()
        val socket = connection.socket
        val packetSize = RtmpHandshake.SIGNAL_SIZE

        assertNull(connection.handshakeDurationMillis)
        socket.onConnect()
        socket.onInput(ByteBuffer.allocate(1 + packetSize))
        socket.onInput(ByteBuffer.allocate(packetSize - 1))
        assertNull(connection.handshakeDurationMillis)

        socket.onInput(ByteBuffer.allocate(packetSize))
        assertNotNull(connection.handshakeDurationMillis)
    }
}
