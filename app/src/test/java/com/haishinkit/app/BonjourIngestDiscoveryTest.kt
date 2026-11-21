package com.haishinkit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

class BonjourIngestDiscoveryTest {
    @Test
    fun selectsIpv4WhenAnAdvertisementAlsoHasUnsupportedScopedIpv6() {
        val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 3)
        assertEquals("192.168.1.20", supportedIngestHost(listOf(scoped, InetAddress.getByName("192.168.1.20"))))
        assertNull(supportedIngestHost(listOf(scoped)))
        assertNull(supportedIngestHost(listOf(InetAddress.getByName("fe80::1234"))))
    }

    @Test
    fun acceptsUnscopedGlobalIpv6WhenIpv4IsUnavailable() {
        val address = InetAddress.getByName("2001:db8::1234")
        assertEquals(address.hostAddress, supportedIngestHost(listOf(address)))
    }

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
