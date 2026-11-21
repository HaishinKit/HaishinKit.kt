package com.haishinkit.app

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNsdManager
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BonjourDiscoveryLifecycleTest {
    private lateinit var discovery: BonjourIngestDiscovery
    private lateinit var nsd: ShadowNsdManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        discovery = BonjourIngestDiscovery(context)
        nsd = Shadow.extract(context.getSystemService(NsdManager::class.java))
    }

    @Test
    fun discoversReceiverButIgnoresSourceAndRemovesLostReceiver() {
        discovery.start()
        val listener = nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single()
        val receiver = service("Mac", "receiver")
        val source = service("Camera", "source")
        listener.onServiceFound(receiver)
        listener.onServiceFound(source)
        drain()
        nsd.getResolveListeners(receiver).orEmpty().single().onServiceResolved(receiver)
        drain()
        nsd.getResolveListeners(source).orEmpty().single().onServiceResolved(source)
        drain()
        assertEquals(listOf("Mac"), discovery.state.value.endpoints.map { it.name })
        listener.onServiceLost(receiver)
        drain()
        assertTrue(discovery.state.value.endpoints.isEmpty())
        discovery.stop()
        assertFalse(discovery.state.value.searching)
        assertTrue(nsd.getDiscoveryListeners("_srt._udp.").orEmpty().isEmpty())
    }

    @Test
    fun lateResolutionCannotRestoreLostService() {
        discovery.start()
        val listener = nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single()
        val receiver = service("Mac", "receiver")
        listener.onServiceFound(receiver)
        drain()
        listener.onServiceLost(receiver)
        nsd.getResolveListeners(receiver).orEmpty().single().onServiceResolved(receiver)
        drain()
        assertTrue(discovery.state.value.endpoints.isEmpty())
        discovery.stop()
    }

    @Test
    fun callbacksFromPreviousSearchCannotPolluteNewSearch() {
        discovery.start()
        val oldListener = nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single()
        val receiver = service("Old Mac", "receiver")
        oldListener.onServiceFound(receiver)
        drain()
        val oldResolution = nsd.getResolveListeners(receiver).orEmpty().single()
        discovery.start()
        oldResolution.onServiceResolved(receiver)
        oldListener.onStartDiscoveryFailed("_srt._udp.", NsdManager.FAILURE_INTERNAL_ERROR)
        drain()
        assertTrue(discovery.state.value.searching)
        assertFalse(discovery.state.value.failed)
        assertTrue(discovery.state.value.endpoints.isEmpty())
        discovery.stop()
    }

    @Test
    fun refreshWaitsForOldResolutionBeforeResolvingNewAdvertisement() {
        discovery.start()
        val oldService = service("Old Mac", "receiver")
        nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single().onServiceFound(oldService)
        drain()
        val oldResolution = nsd.getResolveListeners(oldService).orEmpty().single()
        discovery.start()
        val newService = service("New Mac", "receiver")
        nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single().onServiceFound(newService)
        drain()
        assertTrue(nsd.getResolveListeners(newService).orEmpty().isEmpty())
        oldResolution.onServiceResolved(oldService)
        drain()
        assertTrue(discovery.state.value.endpoints.isEmpty())
        nsd.getResolveListeners(newService).orEmpty().single().onServiceResolved(newService)
        drain()
        assertEquals(listOf("New Mac"), discovery.state.value.endpoints.map { it.name })
        discovery.stop()
    }

    @Test
    fun failedOldResolutionAlsoReleasesSlotForNewSearch() {
        discovery.start()
        val oldService = service("Old Mac", "receiver")
        nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single().onServiceFound(oldService)
        drain()
        val oldResolution = nsd.getResolveListeners(oldService).orEmpty().single()
        discovery.start()
        val newService = service("New Mac", "receiver")
        nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single().onServiceFound(newService)
        drain()
        assertTrue(nsd.getResolveListeners(newService).orEmpty().isEmpty())
        oldResolution.onResolveFailed(oldService, NsdManager.FAILURE_INTERNAL_ERROR)
        drain()
        assertFalse(discovery.state.value.failed)
        nsd.getResolveListeners(newService).orEmpty().single().onServiceResolved(newService)
        drain()
        assertEquals(listOf("New Mac"), discovery.state.value.endpoints.map { it.name })
        discovery.stop()
    }

    @Test
    fun rediscoveryCannotExposeAnOldAddressWhenReplacementFails() {
        discovery.start()
        val listener = nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single()
        val oldService = service("Mac", "receiver")
        listener.onServiceFound(oldService)
        drain()
        val oldResolution = nsd.getResolveListeners(oldService).orEmpty().single()
        listener.onServiceLost(oldService)
        val replacement = service("Mac", "receiver").apply { port = 9720 }
        listener.onServiceFound(replacement)
        drain()
        oldResolution.onServiceResolved(oldService)
        drain()
        assertTrue(discovery.state.value.endpoints.isEmpty())
        nsd.getResolveListeners(replacement).orEmpty().single { it !== oldResolution }.onResolveFailed(replacement, NsdManager.FAILURE_INTERNAL_ERROR)
        drain()
        assertTrue(discovery.state.value.endpoints.isEmpty())
        discovery.stop()
    }

    @Test
    fun scopedIpv6ReceiverIsNotSelectable() {
        discovery.start()
        val listener = nsd.getDiscoveryListeners("_srt._udp.").orEmpty().single()
        val receiver =
            service("IPv6 Mac", "receiver").apply {
                host = java.net.Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 3)
            }
        listener.onServiceFound(receiver)
        drain()
        nsd.getResolveListeners(receiver).orEmpty().single().onServiceResolved(receiver)
        drain()
        assertTrue(discovery.state.value.endpoints.isEmpty())
        discovery.stop()
    }

    private fun service(
        name: String,
        role: String,
    ) =
        NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_srt._udp."
            host = InetAddress.getByName("192.168.1.20")
            port = 9710
            setAttribute("role", role)
        }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
}
