package com.haishinkit.rtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class HTTPTransportTest {
    @Test fun postResolvesRelativeLocationAndDeleteUsesAuthentication() {
        server { server, endpoint ->
            var posted = ""
            var deleted = false
            server.createContext("/live/whip") { request ->
                assertEquals("POST", request.requestMethod)
                assertEquals("Bearer secret", request.requestHeaders.getFirst("Authorization"))
                assertEquals("application/sdp", request.requestHeaders.getFirst("Content-Type"))
                posted = request.requestBody.bufferedReader().readText()
                request.responseHeaders.add("Location", endpoint.resolve("/live/sessions/123").toString().replace("http:", "HTTP:"))
                request.responseHeaders.add("Content-Type", "application/sdp; charset=utf-8")
                request.sendResponseHeaders(201, 6)
                request.responseBody.use { it.write("answer".toByteArray()) }
            }
            server.createContext("/live/sessions/123") { request ->
                assertEquals("DELETE", request.requestMethod)
                assertEquals("Bearer secret", request.requestHeaders.getFirst("Authorization"))
                deleted = true
                request.sendResponseHeaders(204, -1)
                request.close()
            }
            val http = HTTPTransport(RTCConfiguration(bearerToken = "secret"))
            var allocated: HTTPTransport.Resource? = null
            val resource = http.create(URI(endpoint.resolve("/live/whip").toString().replace("http:", "HTTP:")), "offer") { allocated = it }
            assertEquals("offer", posted)
            assertEquals("answer", resource.answer)
            assertEquals(endpoint.resolve("/live/sessions/123"), allocated!!.uri)
            http.delete(resource)
            assertTrue(deleted)
        }
    }

    @Test fun malformedAnswerStillRecordsResourceForCleanup() {
        server { server, endpoint ->
            server.createContext("/whip") { request ->
                request.requestBody.close()
                request.responseHeaders.add("Location", "/resource")
                request.responseHeaders.add("Content-Type", "text/html")
                request.sendResponseHeaders(201, -1)
                request.close()
            }
            var resource: HTTPTransport.Resource? = null
            assertThrows(IOException::class.java) { HTTPTransport(RTCConfiguration()).create(endpoint.resolve("/whip"), "offer") { resource = it } }
            assertEquals(endpoint.resolve("/resource"), resource!!.uri)
        }
    }

    @Test fun rejectsServerErrorsWithoutAcceptingTheirBodyAsSdp() {
        server { server, endpoint ->
            server.createContext("/whip") { request ->
                request.requestBody.close()
                request.sendResponseHeaders(401, -1)
                request.close()
            }
            assertThrows(IOException::class.java) { HTTPTransport(RTCConfiguration()).create(endpoint.resolve("/whip"), "offer") { fail("No resource should be allocated") } }
        }
    }

    @Test fun redirectsPreservePostAndStripAuthorizationAcrossOrigins() {
        server { other, second ->
            other.createContext("/whip") { request ->
                assertEquals("POST", request.requestMethod)
                assertNull(request.requestHeaders.getFirst("Authorization"))
                assertEquals("offer", request.requestBody.bufferedReader().readText())
                request.responseHeaders.add("Location", "/session")
                request.responseHeaders.add("Content-Type", "application/sdp")
                request.sendResponseHeaders(201, 6)
                request.responseBody.use { it.write("answer".toByteArray()) }
            }
            server { first, endpoint ->
                first.createContext("/whip") { request ->
                    request.requestBody.close()
                    request.responseHeaders.add("Location", second.resolve("/whip").toString().replace("http:", "HTTP:"))
                    request.sendResponseHeaders(307, -1)
                    request.close()
                }
                val resource = HTTPTransport(RTCConfiguration(bearerToken = "secret")).create(endpoint.resolve("/whip"), "offer") { }
                assertNull(resource.authorization)
            }
        }
    }

    @Test fun absoluteDeadlineStopsContinuouslyDrippingSdpAndRetainsCleanupLocation() {
        server { server, endpoint ->
            server.createContext("/whip") { it.sendDrippingSDP("/resource") }
            var deleted = false
            server.createContext("/resource") {
                deleted = true
                it.sendResponseHeaders(204, -1)
                it.close()
            }
            val transport = HTTPTransport(RTCConfiguration(httpTimeoutMillis = 500))
            val allocated = AtomicReference<HTTPTransport.Resource>()
            val start = System.nanoTime()
            assertThrows(IOException::class.java) {
                runBlocking { transport.createCancellable(endpoint.resolve("/whip"), "offer") { allocated.set(it) } }
            }
            assertTrue("An inactivity-only timeout let the response run too long", (System.nanoTime() - start) / 1_000_000 < 3000)
            assertNotNull(allocated.get())
            runBlocking { transport.deleteCancellable(allocated.get()) }
            assertTrue(deleted)
        }
    }

    @Test fun cancellationDisconnectsAnActiveBodyReadAndAllowsAnotherRequest() {
        server { server, endpoint ->
            server.createContext("/whip") { it.sendDrippingSDP("/resource") }
            server.createContext("/resource") {
                it.sendResponseHeaders(204, -1)
                it.close()
            }
            val transport = HTTPTransport(RTCConfiguration())
            runBlocking {
                val allocated = CompletableDeferred<HTTPTransport.Resource>()
                val request = async { transport.createCancellable(endpoint.resolve("/whip"), "offer") { allocated.complete(it) } }
                val resource = withTimeout(3000) { allocated.await() }
                val start = System.nanoTime()
                withTimeout(2000) { request.cancelAndJoin() }
                assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
                transport.deleteCancellable(resource)
            }
        }
    }

    @Test fun redirectHopsShareAnAbsoluteDeadline() {
        server { server, endpoint ->
            for (hop in 0..2) {
                server.createContext("/hop$hop") { request ->
                    try {
                        Thread.sleep(200)
                        if (hop < 2) {
                            request.responseHeaders.add("Location", "/hop${hop + 1}")
                            request.sendResponseHeaders(307, -1)
                            request.close()
                        } else {
                            request.responseHeaders.add("Location", "/resource")
                            request.responseHeaders.add("Content-Type", "application/sdp")
                            request.sendResponseHeaders(201, 6)
                            request.responseBody.use { it.write("answer".toByteArray()) }
                        }
                    } catch (_: IOException) {
                        request.close()
                    }
                }
            }
            val transport = HTTPTransport(RTCConfiguration(httpTimeoutMillis = 500))
            val failure =
                assertThrows(IOException::class.java) {
                    runBlocking { transport.createCancellable(endpoint.resolve("/hop0"), "offer") { } }
                }
            assertEquals("HTTP SDP request timed out", failure.message)
        }
    }

    @Test fun absoluteDeadlineAbortsDrippingResponseHeaders() {
        server { server, endpoint ->
            server.createContext("/whip") { request ->
                try {
                    request.responseBody.write("HTTP/1.1 201 Created\r\nX-Padding: ".toByteArray())
                    request.responseBody.flush()
                    repeat(200) {
                        request.responseBody.write('x'.code)
                        request.responseBody.flush()
                        Thread.sleep(20)
                    }
                    request.responseBody.write("\r\nLocation: /resource\r\nContent-Type: application/sdp\r\nContent-Length: 6\r\n\r\nanswer".toByteArray())
                    request.responseBody.flush()
                } catch (_: IOException) {
                } finally {
                    request.close()
                }
            }
            val transport = HTTPTransport(RTCConfiguration(httpTimeoutMillis = 500))
            val start = System.nanoTime()
            assertThrows(IOException::class.java) {
                runBlocking { transport.createCancellable(endpoint.resolve("/whip"), "offer") { } }
            }
            assertTrue("A header read ignored the absolute deadline", (System.nanoTime() - start) / 1_000_000 < 2500)
        }
    }

    private fun server(
        block: (TestHTTPServer, URI) -> Unit,
    ) {
        val server = TestHTTPServer()
        server.start()
        try {
            block(server, URI("http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop()
        }
    }
}
