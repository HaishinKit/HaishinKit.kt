package com.haishinkit.rtmp

import com.haishinkit.net.NetSocket
import com.haishinkit.net.NetSocketImpl
import org.junit.Assert.assertEquals
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
        val listener = startHandshake(socket)
        listener.onInput(ByteBuffer.allocate(1 + packetSize))
        listener.onInput(ByteBuffer.allocate(packetSize - 1))
        assertNull(connection.handshakeDurationMillis)

        listener.onInput(ByteBuffer.allocate(packetSize))
        assertNotNull(connection.handshakeDurationMillis)
    }

    @Test
    fun acceptsS2FollowedByAnIncompleteRtmpChunk() {
        val connection = RtmpConnection()
        val listener = startHandshake(connection.socket)
        val handshakeSize = 1 + 2 * RtmpHandshake.SIGNAL_SIZE
        val input = ByteBuffer.allocate(handshakeSize + 1)
        input.put(handshakeSize, 3)

        listener.onInput(input)

        assertNotNull(connection.handshakeDurationMillis)
        assertEquals(handshakeSize, input.position())
    }

    @Test
    fun ignoresS2FromAReplacedTransport() {
        val connection = RtmpConnection()
        val packetSize = RtmpHandshake.SIGNAL_SIZE
        val oldListener = startHandshake(connection.socket)
        oldListener.onInput(ByteBuffer.allocate(1 + packetSize))

        val newListener = startHandshake(connection.socket)
        newListener.onInput(ByteBuffer.allocate(1 + packetSize))
        oldListener.onInput(ByteBuffer.allocate(packetSize))
        assertNull(connection.handshakeDurationMillis)

        newListener.onInput(ByteBuffer.allocate(packetSize))
        assertNotNull(connection.handshakeDurationMillis)
    }

    private fun startHandshake(socket: RtmpSocket): NetSocket.Listener {
        val transport =
            object : NetSocket by NetSocketImpl() {
                override fun connect(
                    dstName: String,
                    dstPort: Int,
                    isSecure: Boolean,
                ) = Unit

                override fun doOutput(buffer: ByteBuffer) = Unit
            }
        socket.connect("localhost", 1935, false, transport)
        return checkNotNull(transport.listener).also { it.onConnect() }
    }
}
