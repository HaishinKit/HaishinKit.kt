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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
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
    fun parsesCompleteRtmpChunkReceivedAlongsideS2() {
        val connection = RtmpConnection()
        val listener = startHandshake(connection.socket)
        val handshakeSize = 1 + 2 * RtmpHandshake.SIGNAL_SIZE
        val input = ByteBuffer.allocate(handshakeSize + 16)
        input.position(handshakeSize)
        input.putInt(2 shl 24) // Type-0 control chunk.
        input.putInt((4 shl 8) or 1) // Four-byte Set Chunk Size message.
        input.putInt(0)
        input.putInt(4096)
        input.flip()

        listener.onInput(input)

        assertNotNull(connection.handshakeDurationMillis)
        assertEquals(4096, connection.socket.chunkSizeC)
        assertEquals(input.limit(), input.position())
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
    fun measuresMonotonicMillisecondsAndRetainsThemUntilNextAttempt() {
        var now = 1_000_000_000L
        val socket = RtmpSocket(RtmpConnection()) { now }
        val listener = startHandshake(socket)
        listener.onInput(ByteBuffer.allocate(1 + RtmpHandshake.SIGNAL_SIZE))
        now += 23_999_999L
        assertNull(socket.handshakeDurationMillis)
        listener.onInput(ByteBuffer.allocate(RtmpHandshake.SIGNAL_SIZE))
        assertEquals(23L, socket.handshakeDurationMillis)

        socket.close(false)
        assertEquals(23L, socket.handshakeDurationMillis)
        startHandshake(socket)
        assertNull(socket.handshakeDurationMillis)
    }

    @Test
    fun playbackBufferListenerCanWaitForCloseOnAnotherThread() {
        val connection = RtmpConnection()
        val stream = RtmpStream(RuntimeEnvironment.getApplication(), connection)
        val audioCodec =
            com.haishinkit.stream.Stream::class.java.getDeclaredMethod("getAudioCodec")
                .apply { isAccessible = true }.invoke(stream) as com.haishinkit.codec.AudioCodec
        // This test exercises callback locking, with an already accepted AAC decoder.
        com.haishinkit.stream.Stream::class.java.getDeclaredMethod("setMode", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(stream, com.haishinkit.codec.Codec.MODE_DECODE)
        audioCodec.isRunning.set(true)
        audioCodec.inputMimeType = android.media.MediaFormat.MIMETYPE_AUDIO_AAC
        RtmpStream::class.java.getDeclaredField("audioConfigurationAccepted").apply { isAccessible = true }.setBoolean(stream, true)
        val executor = Executors.newSingleThreadExecutor()
        var notified = false
        try {
            val listener = startHandshake(connection.socket)
            listener.onInput(ByteBuffer.allocate(1 + 2 * RtmpHandshake.SIGNAL_SIZE))
            stream.id = 1
            connection.streams[1] = stream
            stream.addEventListener(
                Event.RTMP_STATUS,
                object : IEventListener {
                    override fun handleEvent(event: Event) {
                        if ((event.data as? Map<*, *>)?.get("code") == RtmpStream.Code.BUFFER_FULL.rawValue) {
                            notified = true
                            executor.submit { connection.close() }.get(3, TimeUnit.SECONDS)
                        }
                    }
                },
            )
            val input = ByteBuffer.allocate(15)
            input.putInt((4 shl 24) or 300) // Type-0 audio chunk, 300 ms timestamp.
            input.putInt((3 shl 8) or 8) // Three-byte audio message.
            input.put(byteArrayOf(1, 0, 0, 0)) // Little-endian stream ID.
            input.put(byteArrayOf(0xaf.toByte(), 1, 0)) // Raw AAC data for the accepted decoder.
            input.flip()
            listener.onInput(input)
            assertTrue(notified)
            assertEquals(false, connection.isConnected)
        } finally {
            audioCodec.isRunning.set(false)
            executor.shutdownNow()
            connection.close()
            stream.dispose()
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
