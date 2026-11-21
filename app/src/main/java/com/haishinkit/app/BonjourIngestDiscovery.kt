package com.haishinkit.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

internal data class IngestEndpoint(val id: String, val name: String, val host: String, val port: Int) {
    val uri: String
        get() = srtIngestUri(host, port)
}

internal fun srtIngestUri(
    host: String,
    port: Int,
): String {
    require(host.isNotBlank() && port in 1..65535) { "Invalid SRT receiver address" }
    return URI("srt", null, host, port, null, "mode=caller", null).toASCIIString()
}

internal fun supportedIngestHost(addresses: List<InetAddress>): String? {
    val address =
        addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull { it !is Inet6Address || (it.scopeId == 0 && !it.isLinkLocalAddress) }
    return address?.hostAddress
}

/** Discovers opt-in SRT receivers. DNS-SD resolution is serialized for older Android releases. */
@Suppress("DEPRECATION")
internal class BonjourIngestDiscovery(context: Context) {
    data class State(val searching: Boolean = false, val endpoints: List<IngestEndpoint> = emptyList(), val failed: Boolean = false)

    val state get() = mutableState.asStateFlow()

    private val mutableState = MutableStateFlow(State())
    private val application = context.applicationContext
    private val nsd = application.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private class Advertisement(val service: NsdServiceInfo)

    private val pending = linkedMapOf<String, Advertisement>()
    private val present = mutableMapOf<String, Advertisement>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var generation = 0
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        stop()
        val run = generation
        mutableState.value = State(searching = true)
        val callback =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit

                override fun onServiceFound(service: NsdServiceInfo) {
                    handler.post {
                        if (run != generation) return@post
                        val id = service.serviceName + "/" + service.serviceType
                        val advertisement = Advertisement(service)
                        present[id] = advertisement
                        pending[id] = advertisement
                        mutableState.value = mutableState.value.copy(endpoints = mutableState.value.endpoints.filterNot { it.id == id })
                        resolveNext(run)
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    handler.post {
                        if (run != generation) return@post
                        val id = service.serviceName + "/" + service.serviceType
                        present.remove(id)
                        pending.remove(id)
                        mutableState.value = mutableState.value.copy(endpoints = mutableState.value.endpoints.filterNot { it.id == id })
                    }
                }

                override fun onDiscoveryStopped(type: String) = Unit

                override fun onStartDiscoveryFailed(
                    type: String,
                    error: Int,
                ) {
                    handler.post {
                        if (run == generation) {
                            stop()
                            mutableState.value = State(failed = true)
                        }
                    }
                }

                override fun onStopDiscoveryFailed(
                    type: String,
                    error: Int,
                ) = Unit
            }
        listener = callback
        try {
            if (Build.VERSION.SDK_INT < 34) {
                multicastLock =
                    application.getSystemService(WifiManager::class.java)
                        .createMulticastLock("HaishinKit-Ingest").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
            }
            nsd.discoverServices("_srt._udp.", NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (_: RuntimeException) {
            stop()
            mutableState.value = State(failed = true)
        }
    }

    fun stop() {
        generation++
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        pending.clear()
        present.clear()
        // Legacy NSD cannot cancel an in-flight resolution. Its callback releases the slot.
        mutableState.value = State()
    }

    private fun resolveNext(run: Int) {
        if (resolving || run != generation) return
        val entry = pending.entries.firstOrNull() ?: return
        pending.remove(entry.key)
        resolving = true
        try {
            nsd.resolveService(
                entry.value.service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        service: NsdServiceInfo,
                        error: Int,
                    ) {
                        handler.post {
                            resolving = false
                            if (run == generation && present[entry.key] === entry.value) {
                                mutableState.value = mutableState.value.copy(failed = true)
                            }
                            resolveNext(generation)
                        }
                    }

                    override fun onServiceResolved(service: NsdServiceInfo) {
                        handler.post {
                            resolving = false
                            val role = service.attributes["role"]?.toString(Charsets.UTF_8)
                            val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
                            val host = supportedIngestHost(addresses)
                            if (run == generation && present[entry.key] === entry.value && role == "receiver" && host != null && service.port in 1..65535) {
                                val endpoint = IngestEndpoint(entry.key, service.serviceName, host, service.port)
                                mutableState.value =
                                    mutableState.value.copy(
                                        endpoints = (mutableState.value.endpoints.filterNot { it.id == endpoint.id } + endpoint).sortedBy { it.name },
                                    )
                            }
                            resolveNext(generation)
                        }
                    }
                },
            )
        } catch (_: RuntimeException) {
            resolving = false
            mutableState.value = mutableState.value.copy(failed = true)
            resolveNext(run)
        }
    }
}
