package com.haishinkit.srt

import java.net.URI
import java.net.URLDecoder

/** URI parsing without Android dependencies or network I/O. */
internal class SrtEndpoint(uri: URI, defaults: SrtConnection.Options) {
    val mode: SrtConnection.Mode
    val host: String
    val port: Int
    val adapter: String?
    val localPort: Int?
    val socketOptions: SrtSocket.Options

    init {
        require(uri.scheme.equals("srt", ignoreCase = true) && !uri.isOpaque) { "Expected an SRT URI" }
        require(uri.rawUserInfo == null && uri.rawFragment == null) { "User info and fragments are not supported" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "SRT URIs do not support a stream path; use streamid" }
        val authority = requireNotNull(uri.rawAuthority) { "SRT URI requires an authority" }
        host = uri.host?.removeSurrounding("[", "]") ?: run {
            require(authority.startsWith(":")) { "Invalid SRT host" }
            ""
        }
        port =
            if (host.isEmpty()) {
                authority.removePrefix(":").toIntOrNull() ?: throw IllegalArgumentException("Invalid SRT port")
            } else {
                if (uri.port == -1) 9710 else uri.port
            }
        require(port in 0..65535) { "Invalid SRT port" }
        val query = mutableMapOf<String, String>()
        uri.rawQuery?.takeIf { it.isNotEmpty() }?.split("&")?.forEach { item ->
            val pair = item.split("=", limit = 2)
            require(pair.size == 2) { "SRT query parameters require values" }
            val key = decode(pair[0])
            require(key in SUPPORTED_KEYS && !query.containsKey(key)) { "Unsupported or duplicate SRT query parameter" }
            query[key] = decode(pair[1])
        }
        mode =
            when (query["mode"]) {
                null -> if (host.isEmpty()) SrtConnection.Mode.LISTENER else SrtConnection.Mode.CALLER
                "caller", "client" -> SrtConnection.Mode.CALLER
                "listener", "server" -> SrtConnection.Mode.LISTENER
                "rendezvous" -> SrtConnection.Mode.RENDEZVOUS
                else -> throw IllegalArgumentException("Unsupported SRT mode")
            }
        require(mode == SrtConnection.Mode.LISTENER || (host.isNotEmpty() && port > 0)) { "A remote host and port are required" }
        adapter = query["adapter"]?.also { require(it.isNotEmpty()) { "Adapter must not be empty" } }
        localPort =
            query["port"]?.let {
                it.toIntOrNull()?.also { value -> require(value in 0..65535) { "Invalid local port" } }
                    ?: throw IllegalArgumentException("Invalid local port")
            }
        val latency =
            query["latency"]?.let {
                val micros = it.toLongOrNull() ?: throw IllegalArgumentException("Invalid latency")
                require(micros in 0..Int.MAX_VALUE.toLong() * 1000) { "Invalid latency" }
                ((micros + 999) / 1000).toInt()
            } ?: defaults.latencyMs
        val timeout =
            query["conntimeo"]?.toIntOrNull()
                ?: if (query.containsKey("conntimeo")) throw IllegalArgumentException("Invalid connection timeout") else defaults.connectTimeoutMs
        val options = SrtConnection.Options(latency, timeout, query["passphrase"] ?: defaults.passphrase, query["streamid"] ?: defaults.streamId)
        socketOptions = SrtSocket.Options(options.latencyMs, options.connectTimeoutMs, options.passphrase, options.streamId)
    }

    companion object {
        private val SUPPORTED_KEYS = setOf("mode", "adapter", "port", "latency", "conntimeo", "passphrase", "streamid")

        private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }
}
