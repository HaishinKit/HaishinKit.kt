package com.haishinkit.srt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class SrtEndpointTest {
    @Test
    fun parsesDefaultPortAndEmptyHostListener() {
        val caller = endpoint("srt://example.com")
        assertEquals(SrtConnection.Mode.CALLER, caller.mode)
        assertEquals(9710, caller.port)
        val listener = endpoint("srt://:0")
        assertEquals(SrtConnection.Mode.LISTENER, listener.mode)
        assertEquals("", listener.host)
        assertEquals(0, listener.port)
    }

    @Test
    fun decodesPercentEncodingAndPreservesLiteralPlus() {
        val parsed = endpoint("srt://example.com:9000?streamid=publish%3Alive+test%3Ftoken%3Da%3Db&passphrase=secret%2B12345")
        assertEquals("publish:live+test?token=a=b", parsed.socketOptions.streamId)
        assertEquals("secret+12345", parsed.socketOptions.passphrase)
    }

    @Test
    fun convertsUriLatencyFromMicrosecondsAndAppliesOverrides() {
        val parsed = SrtEndpoint(URI("srt://localhost?latency=120001&conntimeo=200"), SrtConnection.Options(latencyMs = 10))
        assertEquals(121, parsed.socketOptions.latencyMs)
        assertEquals(200, parsed.socketOptions.connectTimeoutMs)
    }

    @Test
    fun rejectsUnsupportedOrAmbiguousConfiguration() {
        listOf(
            "rtmp://localhost:9000",
            "srt://localhost:0",
            "srt://localhost:9000/live",
            "srt://user@localhost:9000",
            "srt://localhost:9000#fragment",
            "srt://localhost:9000?mode=unknown",
            "srt://localhost:9000?latency=-1",
            "srt://localhost:9000?conntimeo=0",
            "srt://localhost:9000?passphrase=short",
            "srt://localhost:9000?latency=1&latency=2",
            "srt://localhost:9000?unsupported=true",
        ).forEach { uri ->
            assertTrue("Expected invalid configuration", runCatching { endpoint(uri) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun parsesIpv6AndRendezvousLocalConfiguration() {
        val parsed = endpoint("srt://[::1]:9000?mode=rendezvous&adapter=::&port=8000")
        assertEquals("::1", parsed.host)
        assertEquals(SrtConnection.Mode.RENDEZVOUS, parsed.mode)
        assertEquals("::", parsed.adapter)
        assertEquals(8000, parsed.localPort)
    }

    private fun endpoint(uri: String) = SrtEndpoint(URI(uri), SrtConnection.Options())
}
