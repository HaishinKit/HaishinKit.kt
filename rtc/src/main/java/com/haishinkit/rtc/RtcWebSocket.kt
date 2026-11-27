package com.haishinkit.rtc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.io.encoding.Base64

/**
 * A single-use libdatachannel WebSocket. TLS verification is always enabled.
 * WSS uses platform trust anchors by default, or an explicitly supplied PEM CA bundle.
 * Receive and send queues are bounded. Cancel/close to release native resources.
 */
class RtcWebSocket(private val caCertificatesPem: String? = null) : Closeable {
    data class Message(val data: ByteArray, val isBinary: Boolean) {
        val text: String get() = data.toString(Charsets.UTF_8)
    }

    private data class NativeEvent(val kind: Int, val bytes: ByteArray)

    private val guard = Any()
    private val opened = CompletableDeferred<Unit>()
    private val messages = Channel<Message>(64)
    private val callbacks = Channel<NativeEvent>(64)
    private val callbackScope = CoroutineScope(Dispatchers.Default)
    private var handle = 0L
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
                System.loadLibrary("hkrtc")
                callbackScope.launch { dispatchEvents() }
                handle = create(uri.toASCIIString(), roots)
            }
            opened.await()
        } catch (cause: Throwable) {
            close()
            throw cause
        }
    }

    /** Sends one UTF-8 text frame. Native buffering is bounded to 1 MiB. */
    fun send(text: String) {
        synchronized(guard) {
            check(handle != 0L) { "WebSocket is closed" }
            sendMessage(handle, text.toByteArray(Charsets.UTF_8), false)
        }
    }

    /** Receives one complete text or binary frame, at most 1 MiB. */
    suspend fun receive(): Message = messages.receive()

    override fun close() {
        val previous =
            synchronized(guard) {
                closed = true
                handle.also { handle = 0L }
            }
        // Never hold the Kotlin guard while native close waits for an in-flight callback.
        try {
            if (previous != 0L) destroy(previous)
        } finally {
            callbackScope.cancel()
            callbacks.cancel()
            opened.cancel()
            messages.cancel()
        }
    }

    /** Never signal user coroutines here: Unconfined continuations can reenter close() inline. */
    @Suppress("unused")
    private fun onEvent(
        kind: Int,
        bytes: ByteArray,
    ) {
        if (callbacks.trySend(NativeEvent(kind, bytes)).isFailure) {
            callbacks.close(IOException("WebSocket callback queue is full"))
        }
    }

    /** A single dispatched worker preserves native event order outside callbackMutex. */
    private suspend fun dispatchEvents() {
        try {
            for (event in callbacks) {
                when (event.kind) {
                    0 -> opened.complete(Unit)
                    1, 4 ->
                        if (messages.trySend(Message(event.bytes, event.kind == 4)).isFailure) {
                            fail("WebSocket receive queue is full")
                        }
                    2 -> fail("WebSocket connection failed")
                    3 -> fail("WebSocket was closed")
                }
            }
        } catch (_: IOException) {
            fail("WebSocket callback queue is full")
        }
    }

    private fun fail(message: String) {
        val error = IOException(message)
        opened.completeExceptionally(error)
        messages.close(error)
    }

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
        private val systemTrustAnchors: String by lazy {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val certificates = factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
            check(certificates.isNotEmpty()) { "No platform TLS trust anchors are available" }
            certificates.joinToString("\n") { certificate ->
                val encoded = Base64.encode(certificate.encoded).chunked(64).joinToString("\n")
                "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
            }
        }
    }
}
