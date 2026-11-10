package com.haishinkit.rtc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** HTTP signaling, separated from Android and the peer so protocol behavior is testable. */
internal class HTTPTransport(private val configuration: RTCConfiguration) {
    data class Resource(val uri: URI, val authorization: String?, val answer: String)

    fun create(
        endpoint: URI,
        offer: String,
        created: (Resource) -> Unit,
    ): Resource {
        validate(endpoint)
        var target = endpoint
        var token = configuration.bearerToken?.let { "Bearer $it" }
        repeat(6) { attempt ->
            val connection = connection(target, "POST", token)
            try {
                connection.setRequestProperty("Content-Type", "application/sdp")
                connection.setRequestProperty("Accept", "application/sdp")
                connection.doOutput = true
                val body = offer.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code == 307 || code == 308) {
                    check(attempt < 5) { "Too many signaling redirects" }
                    val next = target.resolve(connection.getHeaderField("Location") ?: throw IOException("Redirect has no Location"))
                    validate(next)
                    require(target.scheme != "https" || next.scheme == "https") { "HTTPS downgrade is not allowed" }
                    if (!sameOrigin(target, next)) token = null
                    target = next
                } else {
                    if (code != 201) throw IOException("SDP POST failed (HTTP $code)")
                    val location = connection.getHeaderField("Location") ?: throw IOException("SDP response has no Location")
                    val resource = target.resolve(location)
                    validate(resource)
                    require(target.scheme != "https" || resource.scheme == "https") { "HTTPS resource downgrade is not allowed" }
                    // Record the resource before reading/validating the answer so setup failure
                    // can still DELETE the server allocation.
                    val pending = Resource(resource, token.takeIf { sameOrigin(target, resource) }, "")
                    created(pending)
                    val type = connection.contentType?.substringBefore(';')?.trim()
                    if (!type.equals("application/sdp", true)) throw IOException("Expected application/sdp answer")
                    val answer =
                        connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                output.write(chunk, 0, count)
                                if (output.size() > MAX_SDP) throw IOException("SDP answer is too large")
                            }
                            val bytes = output.toByteArray()
                            if (bytes.size > MAX_SDP) throw IOException("SDP answer is too large")
                            bytes.toString(Charsets.UTF_8)
                        }
                    return pending.copy(answer = answer)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many signaling redirects")
    }

    fun delete(resource: Resource) {
        val connection = connection(resource.uri, "DELETE", resource.authorization)
        try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 404 && code != 410) throw IOException("Session DELETE failed (HTTP $code)")
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(
        uri: URI,
        method: String,
        authorization: String?,
    ): HttpURLConnection =
        (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = configuration.httpTimeoutMillis
            readTimeout = configuration.httpTimeoutMillis
            authorization?.let { setRequestProperty("Authorization", it) }
        }

    private fun validate(uri: URI) {
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Expected an HTTP(S) URL without user info or fragment" }
    }

    private fun sameOrigin(
        a: URI,
        b: URI,
    ): Boolean =
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)

    private fun port(uri: URI): Int =
        if (uri.port != -1) {
            uri.port
        } else if (uri.scheme == "https") {
            443
        } else {
            80
        }

    private companion object {
        const val MAX_SDP = 1024 * 1024
    }
}
