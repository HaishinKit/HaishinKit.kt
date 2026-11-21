package com.haishinkit.rtc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class RTCWebSocketTest {
    @Test fun nativeTextAndBinaryFramesPreserveUnicodeAndNul() =
        runBlocking {
            requireNativeTests()
            val server = echoServer()
            val socket = RTCWebSocket()
            try {
                withTimeout(5000) { socket.connect(server.url("/").toString().replace("http:", "ws:")) }
                val text = "こんにちは 🎙️" + 0.toChar() + "끝"
                socket.send(text)
                val first = withTimeout(5000) { socket.receive() }
                val second = withTimeout(5000) { socket.receive() }
                assertFalse(first.isBinary)
                assertTrue(second.isBinary)
                assertEquals(text, first.text)
                assertEquals(text, second.text)
            } finally {
                socket.close()
                server.shutdown()
            }
        }

    @Test fun nativeIPv6LiteralConnectsAndExchangesFrames() =
        runBlocking {
            requireNativeTests()
            val server = echoServer(bindAddress = InetAddress.getByName("::1"))
            val socket = RTCWebSocket()
            try {
                val url = server.url("/ipv6").newBuilder().host("::1").build().toString().replace("http:", "ws:")
                assertTrue(url.contains("["))
                withTimeout(5000) { socket.connect(url) }
                socket.send("IPv6")
                assertEquals("IPv6", withTimeout(5000) { socket.receive() }.text)
            } finally {
                socket.close()
                server.shutdown()
            }
        }

    @Test fun wssVerifiesTrustedCertificateAndHostname() =
        runBlocking {
            requireNativeTests()
            val root = HeldCertificate.Builder().commonName("RTC test root").certificateAuthority(0).build()
            val leaf = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(root).build()
            val certificates = HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
            val server = echoServer(certificates)
            val socket = RTCWebSocket(root.certificatePem())
            try {
                withTimeout(5000) { socket.connect(server.url("/").toString().replace("https:", "wss:")) }
                socket.send("verified")
                assertEquals("verified", withTimeout(5000) { socket.receive() }.text)
            } finally {
                socket.close()
                server.shutdown()
            }
        }

    @Test fun wssRejectsHostnameMismatch() =
        runBlocking {
            requireNativeTests()
            val root = HeldCertificate.Builder().commonName("RTC test root").certificateAuthority(0).build()
            val leaf = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(root).build()
            val certificates = HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
            val server = echoServer(certificates)
            val socket = RTCWebSocket(root.certificatePem())
            try {
                val result =
                    runCatching {
                        withTimeout(5000) { socket.connect(server.url("/").toString().replace("https:", "wss:").replace("localhost", "127.0.0.1")) }
                    }
                assertTrue(result.exceptionOrNull() is IOException)
            } finally {
                socket.close()
                server.shutdown()
            }
        }

    @Test fun cancellationWhileOpeningReleasesSocket() =
        runBlocking {
            requireNativeTests()
            val server = MockWebServer()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val socket = RTCWebSocket()
            val connect = async { socket.connect(server.url("/").toString().replace("http:", "ws:")) }
            try {
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                withTimeout(3000) { connect.cancelAndJoin() }
                assertTrue(connect.isCancelled)
            } finally {
                socket.close()
                connect.cancelAndJoin()
                server.shutdown()
            }
        }

    @Test fun closeWhileOpeningIsIdempotentAndPreventsSending() =
        runBlocking {
            requireNativeTests()
            val server = MockWebServer()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val socket = RTCWebSocket()
            val connect = async { socket.connect(server.url("/").toString().replace("http:", "ws:")) }
            try {
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                withContext(Dispatchers.IO) {
                    socket.close()
                    socket.close()
                }
                withTimeout(3000) { connect.join() }
                assertTrue(connect.isCancelled)
                assertThrows(IllegalStateException::class.java) { socket.send("closed") }
                Unit
            } finally {
                socket.close()
                connect.cancelAndJoin()
                server.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun unconfinedReceiverCanCloseAfterNativeMessage() =
        runBlocking {
            requireNativeTests()
            val server = echoServer()
            val socket = RTCWebSocket()
            try {
                withTimeout(5000) { socket.connect(server.url("/").toString().replace("http:", "ws:")) }
                val received =
                    async(Dispatchers.Unconfined) {
                        val text = socket.receive().text
                        socket.close()
                        text
                    }
                socket.send("close from receiver")
                assertEquals("close from receiver", withTimeout(5000) { received.await() })
            } finally {
                socket.close()
                server.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun unconfinedConnectFailureCanCleanUpNativeSocket() =
        runBlocking {
            requireNativeTests()
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            val socket = RTCWebSocket()
            try {
                val result =
                    withContext(Dispatchers.Unconfined) {
                        runCatching {
                            withTimeout(5000) { socket.connect(server.url("/").toString().replace("http:", "ws:")) }
                        }
                    }
                assertTrue(result.exceptionOrNull() is IOException)
            } finally {
                socket.close()
                server.shutdown()
            }
        }

    private fun requireNativeTests() {
        assumeTrue("Set rtcNativeLibraryDir to run native WebSocket tests", System.getProperty("haishinkit.rtc.nativeTests") == "true")
    }

    private fun echoServer(
        tls: HandshakeCertificates? = null,
        bindAddress: InetAddress? = null,
    ): MockWebServer =
        MockWebServer().apply {
            if (tls != null) useHttps(tls.sslSocketFactory(), false)
            enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(code, reason)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            webSocket.send(text)
                            webSocket.send(text.toByteArray(Charsets.UTF_8).toByteString())
                        }
                    },
                ),
            )
            if (bindAddress == null) start() else start(bindAddress, 0)
        }
}
