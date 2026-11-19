package com.haishinkit.rtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A single-use libdatachannel WebSocket. TLS verification is always enabled.
 * WSS uses platform trust anchors by default, or an explicitly supplied PEM CA bundle.
 * Receive and send queues are bounded. Cancel/close to release native resources.
 */
class RTCWebSocket(private val caCertificatesPem: String? = null) : Closeable {
    data class Message(val data: ByteArray, val isBinary: Boolean) {
        val text: String get() = data.toString(Charsets.UTF_8)
    }

    private val guard = Any()
    private val opened = CompletableDeferred<Unit>()
    private val messages = Channel<Message>(64)
    private var native: NativeWebSocket? = null
    private var used = false
    private var closed = false

    suspend fun connect(url: String) {
        val uri = URI(url)
        require(uri.scheme in setOf("ws", "wss") && uri.host != null && uri.userInfo == null)
        synchronized(guard) {
            check(!used && !closed)
            used = true
        }
        try {
            val roots = if (uri.scheme == "wss") caCertificatesPem ?: systemTrustAnchors else ""
            synchronized(guard) {
                check(!closed) { "WebSocket was closed" }
                native =
                    NativeWebSocket(uri.toASCIIString(), roots) { kind, bytes ->
                        when (kind) {
                            0 -> opened.complete(Unit)
                            1, 4 -> if (messages.trySend(Message(bytes, kind == 4)).isFailure) fail("WebSocket receive queue is full")
                            2 -> fail("WebSocket connection failed")
                            3 -> fail("WebSocket was closed")
                        }
                    }
            }
            opened.await()
        } catch (cause: Throwable) {
            close()
            throw cause
        }
    }

    /** Sends one UTF-8 text frame. Native buffering is bounded to 1 MiB. */
    fun send(text: String) {
        synchronized(guard) { checkNotNull(native) { "WebSocket is closed" }.send(text.toByteArray(Charsets.UTF_8), false) }
    }

    /** Receives one complete text or binary frame, at most 1 MiB. */
    suspend fun receive(): Message = messages.receive()

    override fun close() {
        val socket =
            synchronized(guard) {
                closed = true
                native.also { native = null }
            }
        // Never hold the Kotlin guard while native close waits for an in-flight callback.
        socket?.close()
        opened.cancel()
        messages.cancel()
    }

    private fun fail(message: String) {
        val error = IOException(message)
        opened.completeExceptionally(error)
        messages.close(error)
    }

    companion object {
        private val systemTrustAnchors: String by lazy {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val certificates = factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
            check(certificates.isNotEmpty()) { "No platform TLS trust anchors are available" }
            certificates.joinToString("\n") { certificate ->
                "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(certificate.encoded) +
                    "\n-----END CERTIFICATE-----\n"
            }
        }
    }
}

/** JNI callbacks only enqueue messages; they never call close or block native worker threads. */
internal class NativeWebSocket(url: String, roots: String, private val event: (Int, ByteArray) -> Unit) : Closeable {
    private var handle = create(url, roots)

    @Synchronized fun send(
        bytes: ByteArray,
        binary: Boolean,
    ) = sendMessage(handle, bytes, binary)

    override fun close() {
        val previous = synchronized(this) { handle.also { handle = 0 } }
        if (previous != 0L) destroy(previous)
    }

    @Suppress("unused")
    private fun onEvent(
        kind: Int,
        bytes: ByteArray,
    ) = event(kind, bytes)

    private external fun create(
        url: String,
        roots: String,
    ): Long

    private external fun sendMessage(
        handle: Long,
        bytes: ByteArray,
        binary: Boolean,
    )

    private external fun destroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("hkrtc")
        }
    }
}
