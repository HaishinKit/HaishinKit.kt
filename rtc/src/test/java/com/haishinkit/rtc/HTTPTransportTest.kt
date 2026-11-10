package com.haishinkit.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI

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
                request.responseHeaders.add("Location", "sessions/123")
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
            val resource = http.create(endpoint.resolve("/live/whip"), "offer") { allocated = it }
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
                    request.responseHeaders.add("Location", second.resolve("/whip").toString())
                    request.sendResponseHeaders(307, -1)
                    request.close()
                }
                val resource = HTTPTransport(RTCConfiguration(bearerToken = "secret")).create(endpoint.resolve("/whip"), "offer") { }
                assertNull(resource.authorization)
            }
        }
    }

    private fun server(block: (TestHTTPServer, URI) -> Unit) {
        val server = TestHTTPServer()
        server.start()
        try {
            block(server, URI("http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop()
        }
    }
}
