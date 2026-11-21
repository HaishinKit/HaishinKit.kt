package com.haishinkit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class BonjourIngestDiscoveryTest {
    @Test
    fun resolvedIpv4ReceiverUsesAdvertisedPortAndCallerMode() {
        val endpoint = IngestEndpoint("mac/_srt._udp.", "Mac receiver", "192.168.1.20", 9710)
        assertEquals("srt://192.168.1.20:9710?mode=caller", endpoint.uri)
    }

    @Test
    fun ipv6ReceiverIsBracketedWithoutLosingItsScope() {
        val uri = URI(srtIngestUri("fe80::1234%wlan0", 9711))
        assertEquals("[fe80::1234%wlan0]", uri.host)
        assertEquals(9711, uri.port)
        assertEquals("mode=caller", uri.query)
    }

    @Test
    fun invalidResolvedAddressesCannotBecomeDestinations() {
        assertThrows(IllegalArgumentException::class.java) { srtIngestUri("", 9710) }
        assertThrows(IllegalArgumentException::class.java) { srtIngestUri("127.0.0.1", 0) }
        assertThrows(IllegalArgumentException::class.java) { srtIngestUri("127.0.0.1", 65536) }
    }
}
