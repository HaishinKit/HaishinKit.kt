package com.haishinkit.rtmp

import com.haishinkit.net.NetSocket
import com.haishinkit.net.NetSocketImpl
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.event.IEventListener
import com.haishinkit.rtmp.message.RtmpCommandMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test
    fun stopsParsingOldInputWhenMessageCallbackReconnects() {
        val connection = RtmpConnection()
        val socket = connection.socket
        val oldListener = startHandshake(socket)
        val packetSize = RtmpHandshake.SIGNAL_SIZE
        oldListener.onInput(ByteBuffer.allocate(1 + 2 * packetSize))
        connection.addEventListener(
            Event.RTMP_STATUS,
            object : IEventListener {
                override fun handleEvent(event: Event) {
                    connection.close()
                    startHandshake(socket)
                }
            },
        )
        val input = statusMessage(trailingBytes = 16)
        val messageSize = input.position()
        input.putInt(2 shl 24)
        input.putInt((4 shl 8) or 1)
        input.putInt(0)
        input.putInt(4096)
        input.flip()

        oldListener.onInput(input)

        assertNull(connection.handshakeDurationMillis)
        assertEquals(messageSize, input.position())
        assertEquals(RtmpChunk.DEFAULT_SIZE, socket.chunkSizeC)
    }

    @Test
    fun statusListenerCanWaitForCloseOnAnotherThread() {
        val connection = RtmpConnection()
        val listener = startHandshake(connection.socket)
        val executor = Executors.newSingleThreadExecutor()
        try {
            listener.onInput(ByteBuffer.allocate(1 + 2 * RtmpHandshake.SIGNAL_SIZE))
            connection.addEventListener(
                Event.RTMP_STATUS,
                object : IEventListener {
                    override fun handleEvent(event: Event) {
                        executor.submit { connection.close() }.get(3, TimeUnit.SECONDS)
                    }
                },
            )
            listener.onInput(statusMessage().apply { flip() })
            assertEquals(false, connection.isConnected)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun ignoresFailureAfterTransportReplacement() {
        val connection = RtmpConnection()
        val closing = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val transport =
            object : TestTransport() {
                override fun close(disconnected: Boolean) {
                    if (disconnected) {
                        closing.countDown()
                        assertTrue(finishClose.await(3, TimeUnit.SECONDS))
                    }
                }
            }
        val listener = startHandshake(connection.socket, transport)
        val statuses = mutableListOf<Any?>()
        connection.addEventListener(
            Event.RTMP_STATUS,
            object : IEventListener {
                override fun handleEvent(event: Event) {
                    statuses.add(event.data)
                }
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val close = executor.submit { listener.onClose(true) }
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            startHandshake(connection.socket)
            finishClose.countDown()
            close.get(3, TimeUnit.SECONDS)
            assertTrue(statuses.isEmpty())
        } finally {
            finishClose.countDown()
            executor.shutdownNow()
        }
    }

    private fun startHandshake(
        socket: RtmpSocket,
        transport: NetSocket = TestTransport(),
    ): NetSocket.Listener {
        socket.connect("localhost", 1935, false, transport)
        return checkNotNull(transport.listener).also { it.onConnect() }
    }

    private fun statusMessage(trailingBytes: Int = 0): ByteBuffer {
        val command = RtmpCommandMessage(RtmpObjectEncoding.AMF0)
        command.commandName = "_result"
        val payload = ByteBuffer.allocate(128)
        command.encode(payload)
        val payloadSize = payload.position()
        val input = ByteBuffer.allocate(12 + payloadSize + trailingBytes)
        input.putInt(3 shl 24)
        input.putInt((payloadSize shl 8) or command.type.toInt())
        input.putInt(0)
        input.put(payload.array(), 0, payloadSize)
        return input
    }

    private open class TestTransport : NetSocket by NetSocketImpl() {
        override fun connect(
            dstName: String,
            dstPort: Int,
            isSecure: Boolean,
        ) = Unit

        override fun doOutput(buffer: ByteBuffer) = Unit
    }
}
