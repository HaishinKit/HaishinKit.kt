package com.haishinkit.rtc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** HTTP signaling, separated from Android and the peer so protocol behavior is testable. */
internal class HTTPTransport(private val configuration: RTCConfiguration) {
    data class Resource(val uri: URI, val authorization: String?, val answer: String)

    suspend fun createCancellable(
        endpoint: URI,
        offer: String,
        created: (Resource) -> Unit,
    ): Resource =
        cancellable("HTTP SDP request timed out") { call -> create(endpoint, offer, created, call) }

    suspend fun deleteCancellable(resource: Resource) =
        cancellable("HTTP session DELETE timed out") { call -> delete(resource, call) }

    fun create(
        endpoint: URI,
        offer: String,
        created: (Resource) -> Unit,
    ): Resource = create(endpoint, offer, created, Call())

    private fun create(
        endpoint: URI,
        offer: String,
        created: (Resource) -> Unit,
        call: Call,
    ): Resource {
        validate(endpoint)
        var target = endpoint
        var token = configuration.bearerToken?.let { "Bearer $it" }
        repeat(6) { attempt ->
            val connection = connection(target, "POST", token)
            call.attach(connection)
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
                    require(!target.scheme.equals("https", true) || next.scheme.equals("https", true)) { "HTTPS downgrade is not allowed" }
                    if (!sameOrigin(target, next)) token = null
                    target = next
                } else {
                    if (code != 201) throw IOException("SDP POST failed (HTTP $code)")
                    val location = connection.getHeaderField("Location") ?: throw IOException("SDP response has no Location")
                    val resource = target.resolve(location)
                    validate(resource)
                    require(!target.scheme.equals("https", true) || resource.scheme.equals("https", true)) { "HTTPS resource downgrade is not allowed" }
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
                                call.ensureActive()
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
                call.finish(connection)
            }
        }
        throw IOException("Too many signaling redirects")
    }

    fun delete(resource: Resource) = delete(resource, Call())

    private fun delete(
        resource: Resource,
        call: Call,
    ) {
        val connection = connection(resource.uri, "DELETE", resource.authorization)
        call.attach(connection)
        try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 404 && code != 410) throw IOException("Session DELETE failed (HTTP $code)")
        } finally {
            call.finish(connection)
        }
    }

    private suspend fun <T> cancellable(
        message: String,
        block: (Call) -> T,
    ): T =
        withRTCDeadline(configuration.httpTimeoutMillis.toLong(), message) {
            coroutineScope {
                val call = Call()
                val worker =
                    async(Dispatchers.IO) {
                        try {
                            block(call)
                        } catch (cause: Exception) {
                            // A disconnect caused by STOP/deadline is cancellation, not a new
                            // socket failure that should replace the original cancellation.
                            currentCoroutineContext().ensureActive()
                            throw cause
                        }
                    }
                try {
                    worker.await()
                } finally {
                    // Do not queue disconnect behind the blocking I/O workers it
                    // needs to release when the shared I/O dispatcher is saturated.
                    withContext(NonCancellable) {
                        call.cancel()
                        // Wait for any last Location callback before the session deletes
                        // its resource or permits a replacement connection generation.
                        worker.cancelAndJoin()
                    }
                }
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
            setRequestProperty("Connection", "close")
            connectTimeout = configuration.httpTimeoutMillis
            readTimeout = configuration.httpTimeoutMillis
            authorization?.let { setRequestProperty("Authorization", it) }
        }

    private fun validate(uri: URI) {
        require((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Expected an HTTP(S) URL without user info or fragment" }
    }

    private fun sameOrigin(
        a: URI,
        b: URI,
    ): Boolean =
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)

    private fun port(uri: URI): Int =
        if (uri.port != -1) {
            uri.port
        } else if (uri.scheme.equals("https", true)) {
            443
        } else {
            80
        }

    private class Call {
        private val guard = Any()

        @Volatile private var cancelled = false
        private var active: HttpURLConnection? = null

        fun attach(connection: HttpURLConnection) {
            val rejected =
                synchronized(guard) {
                    if (cancelled) {
                        true
                    } else {
                        active = connection
                        false
                    }
                }
            if (rejected) {
                connection.disconnect()
                throw CancellationException("HTTP request cancelled")
            }
        }

        fun ensureActive() {
            if (cancelled) throw CancellationException("HTTP request cancelled")
        }

        fun cancel() {
            val connection =
                synchronized(guard) {
                    cancelled = true
                    active
                }
            connection?.disconnect()
        }

        fun finish(connection: HttpURLConnection) {
            try {
                connection.disconnect()
            } finally {
                synchronized(guard) { if (active === connection) active = null }
            }
        }
    }

    private companion object {
        const val MAX_SDP = 1024 * 1024
    }
}
