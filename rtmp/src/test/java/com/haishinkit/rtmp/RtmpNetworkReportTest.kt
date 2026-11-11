package com.haishinkit.rtmp

import com.haishinkit.rtmp.message.RtmpCommandMessage
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RtmpNetworkReportTest {
    @Test
    fun samplesAfterConnectSuccessAndClearsOnRemoteClose() =
        runBlocking {
            val connection = RtmpConnection()
            ServerSocket(0).use { server ->
                server.soTimeout = 5_000
                connection.connect("rtmp://127.0.0.1:${server.localPort}/live")
                try {
                    server.accept().use { peer ->
                        peer.soTimeout = 5_000
                        val packetSize = RtmpHandshake.SIGNAL_SIZE
                        DataInputStream(peer.getInputStream()).readFully(ByteArray(1 + packetSize))
                        assertNull(connection.networkReport.value)

                        val handshake = ByteArray(1 + 2 * packetSize)
                        handshake[0] = 3
                        peer.getOutputStream().write(handshake)
                        DataInputStream(peer.getInputStream()).readFully(ByteArray(packetSize))
                        val response = connectSuccess()
                        peer.getOutputStream().write(response)

                        val report = withTimeout(5_000) { connection.networkReport.filterNotNull().first() }
                        assertTrue(report.totalBytesIn >= (handshake.size + response.size).toLong())
                        assertTrue(report.totalBytesOut > (1 + 2 * packetSize).toLong())
                    }
                    assertNull(withTimeout(5_000) { connection.networkReport.filter { it == null }.first() })
                } finally {
                    connection.socket.closeAndJoin()
                    connection.dispose()
                }
            }
        }

    private fun connectSuccess(): ByteArray {
        val message = RtmpCommandMessage(RtmpObjectEncoding.AMF0)
        message.commandName = "_result"
        message.transactionID = 1
        message.arguments = listOf(RtmpConnection.Code.CONNECT_SUCCESS.data(""))
        val payload = ByteBuffer.allocate(128)
        message.encode(payload)
        val payloadSize = payload.position()
        return ByteBuffer.allocate(12 + payloadSize)
            .putInt(3 shl 24)
            .putInt((payloadSize shl 8) or message.type.toInt())
            .putInt(0)
            .put(payload.array(), 0, payloadSize)
            .array()
    }
}
