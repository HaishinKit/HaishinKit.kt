package com.haishinkit.rtmp

import com.haishinkit.net.NetSocket
import com.haishinkit.net.NetSocketImpl
import com.haishinkit.rtmp.amf.AmfTypeBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RtmpConnectionCallTest {
    @Test
    fun callsEncodeOnlyTheSuppliedArguments() {
        val connection = RtmpConnection()
        val packets = mutableListOf<ByteBuffer>()
        val transport =
            object : NetSocket by NetSocketImpl() {
                override fun doOutput(buffer: ByteBuffer) {
                    buffer.flip()
                    packets.add(buffer)
                }
            }
        // Capture the real call/AMF encoding path without opening a network connection.
        RtmpSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(connection.socket, transport)
        RtmpSocket::class.java.getDeclaredField("isConnected").apply { isAccessible = true }.setBoolean(connection.socket, true)

        val commands: List<Pair<String, List<Any>>> =
            listOf(
                "createStream" to emptyList(),
                "releaseStream" to listOf("stream-key"),
                "FCPublish" to listOf("stream-key"),
                "custom" to listOf("first", 42.0, true),
            )
        for ((index, command) in commands.withIndex()) {
            connection.call(command.first, null, *command.second.toTypedArray())

            assertEquals(index + 1, packets.size)
            val packet = packets.last()
            packet.position(12) // Type-0 chunk header with a one-byte basic header.
            val decoder = AmfTypeBuffer(packet)
            assertEquals(command.first, decoder.string)
            assertEquals((index + 1).toDouble(), decoder.number, 0.0)
            assertNull(decoder.data) // Command object.
            for (argument in command.second) {
                assertEquals(argument, decoder.data)
            }
            assertFalse(packet.hasRemaining())
        }
    }
}
