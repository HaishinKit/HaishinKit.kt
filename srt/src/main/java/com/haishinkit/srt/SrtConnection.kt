package com.haishinkit.srt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/**
 * Owns one SRT peer connection. [connect] suspends until a caller connects or a
 * listener accepts a peer. No stream multiplexing or media framing is performed.
 *
 * [scope] owns disconnection monitoring; its cancellation closes this connection.
 * Close the connection (for example with use) before leaving that scope normally.
 * After close/failure, this instance can connect again while its scope is active.
 *
 * @param scope Owner of connection monitoring; its context must contain a Job.
 * @param options Default socket configuration, overridden by supported URI query parameters.
 */
class SrtConnection(scope: CoroutineScope, options: Options = Options()) : Closeable {
    /** Handshake role, independent of whether media is sent or received. */
    enum class Mode {
        /** Initiates a connection to a remote listener. */
        CALLER,

        /** Binds locally and waits for one peer. */
        LISTENER,

        /** Binds locally while both peers initiate the handshake. */
        RENDEZVOUS,
    }

    /** Observable lifecycle of the current connection attempt. */
    enum class ReadyState {
        /** No connection attempt or peer socket is owned. */
        CLOSED,

        /** Resolving addresses or establishing a connection. */
        CONNECTING,

        /** The local listener is bound and waiting for a peer. */
        LISTENING,

        /** A peer is connected and transport I/O is available. */
        OPEN,

        /** Setup, monitoring and native sockets are being released. */
        CLOSING,
    }

    /**
     * Default native socket configuration. Typed durations are milliseconds;
     * URI `latency` uses microseconds and is rounded up to whole milliseconds.
     *
     * @property latencyMs Requested receiver/peer latency, at least zero.
     * @property connectTimeoutMs Caller/rendezvous handshake timeout, greater than zero.
     *   This does not limit a listener's wait for its first peer or DNS resolution.
     * @property passphrase Optional encryption passphrase of 10–79 UTF-8 bytes.
     * @property streamId Optional SRT stream identifier, at most 512 UTF-8 bytes.
     * @throws IllegalArgumentException If a duration or string length is invalid.
     */
    class Options(
        val latencyMs: Int = 120,
        val connectTimeoutMs: Int = 3000,
        val passphrase: String? = null,
        val streamId: String? = null,
    ) {
        init {
            require(latencyMs >= 0 && connectTimeoutMs > 0) { "Invalid SRT timing options" }
            require(passphrase == null || passphrase.toByteArray(Charsets.UTF_8).size in 10..79) { "Invalid passphrase length" }
            require(streamId == null || streamId.toByteArray(Charsets.UTF_8).size <= 512) { "Stream ID is too long" }
        }
    }

    /** State of the current attempt. CLOSED is the initial state; fast transitions may be conflated. */
    val readyState: StateFlow<ReadyState>
        get() = state.asStateFlow()

    /** Reads the native peer state; false while connecting, listening or closed. */
    val isConnected: Boolean
        get() = synchronized(guard) { if (state.value == ReadyState.OPEN) active?.socket else null }?.status == SrtSocket.Status.CONNECTED

    /** Original endpoint, including its query, retained until the attempt/connection closes. */
    val uri: URI?
        get() = synchronized(guard) { active?.uri }

    /** Selected handshake role, or null when closed. */
    val mode: Mode?
        get() = synchronized(guard) { active?.mode }

    /** Native local address once bound; null before binding or after close. Port zero exposes the allocated port here. */
    val localAddress: InetSocketAddress?
        get() {
            val socket = synchronized(guard) { active?.let { it.socket ?: it.listener } } ?: return null
            if (socket.status == SrtSocket.Status.INIT || socket.status == SrtSocket.Status.CLOSED) return null
            return try {
                socket.localAddress
            } catch (error: IllegalStateException) {
                if (socket.status == SrtSocket.Status.CLOSED) null else throw error
            }
        }

    /**
     * The latest statistics snapshot for the open peer, sampled about once per second.
     * Returns null before the first sample or when the connection is not open.
     * Each sample resets the native interval counters. Reading this property returns
     * the cached snapshot without resetting the interval.
     */
    val performanceData: SrtPerformanceData?
        get() =
            synchronized(guard) {
                if (state.value == ReadyState.OPEN) active?.performanceData else null
            }

    /**
     * Cold, unbuffered message flow for a single collector. Each collection uses
     * the currently open peer. Completion or cancellation closes that peer.
     * Concurrent readers are rejected without interrupting the existing reader.
     * I/O failures propagate to the collector; no reconnection is automatic.
     */
    val inputs: Flow<ByteArray>
        get() =
            flow {
                val (attempt, socket) = connected()
                try {
                    socket.inputs.collect { emit(it) }
                } finally {
                    closeIfSocketClosed(attempt, socket)
                }
            }

    internal val ownerScope: CoroutineScope
        get() = scope

    private val scope = scope
    private val options = options
    private val guard = Any()
    private val state = MutableStateFlow(ReadyState.CLOSED)
    private var active: Attempt? = null
    private var streamOwner: Any? = null

    init {
        requireNotNull(scope.coroutineContext[Job]) { "The owning scope must contain a Job" }
    }

    /**
     * Parses an endpoint string and connects using [connect].
     *
     * @param uri An SRT URI such as `srt://host:9000?mode=caller`.
     * @throws IllegalArgumentException If the endpoint or a query option is unsupported.
     * @throws java.net.URISyntaxException If the string is not a valid URI.
     * @throws java.io.IOException If address resolution or native connection setup fails.
     * @throws CancellationException If setup is cancelled or its handshake times out.
     */
    suspend fun connect(uri: String) = connect(URI(uri))

    /**
     * Establishes one peer connection. In listener mode this suspends until accept;
     * observe LISTENING and [localAddress] to discover a dynamically allocated port.
     *
     * Supports `mode` (caller/client, listener/server, rendezvous), `adapter` (local
     * bind address), `port` (local port), `latency` (microseconds), `conntimeo`
     * (milliseconds), `passphrase`, and `streamid`. Unknown and duplicate keys are
     * rejected. Percent encoding is decoded; literal plus signs are preserved.
     * The default port is 9710. An empty host selects listener mode; other hosts
     * select caller mode unless mode is explicit. IPv4, IPv6 and DNS names are supported.
     *
     * Caller local binds default to an ephemeral port; rendezvous local binds
     * default to the remote port. Use [close] or caller cancellation to stop an
     * indefinite accept wait. A failed/cancelled attempt releases all resources.
     *
     * @param uri Endpoint and optional socket configuration; paths, user info and fragments are unsupported.
     * @throws IllegalStateException If another connection attempt or peer is owned.
     * @throws IllegalArgumentException If configuration is invalid or unsupported.
     * @throws java.io.IOException If address resolution or native setup fails.
     * @throws CancellationException If setup is cancelled or its handshake times out.
     */
    suspend fun connect(uri: URI): Unit =
        coroutineScope {
            scope.coroutineContext.ensureActive()
            val endpoint = SrtEndpoint(uri, options)
            val attempt = Attempt(currentCoroutineContext()[Job]!!, uri, endpoint.mode)
            synchronized(guard) {
                check(active == null) { "Connection is already open or connecting" }
                active = attempt
                state.value = ReadyState.CONNECTING
            }
            try {
                val monitor =
                    scope.launch(start = CoroutineStart.LAZY) {
                        val peer = attempt.peer.await()
                        val statistics = launch { samplePerformanceData(attempt, peer) }
                        try {
                            peer.awaitDisconnected()
                        } finally {
                            statistics.cancel()
                        }
                    }
                try {
                    synchronized(guard) {
                        checkCurrent(attempt)
                        attempt.monitor = monitor
                    }
                } catch (error: Throwable) {
                    monitor.cancel()
                    throw error
                }
                monitor.invokeOnCompletion { closeAttempt(attempt) }
                monitor.start()
                val remote = if (endpoint.mode == Mode.LISTENER) null else resolve(endpoint.host, endpoint.port)
                val local =
                    when (endpoint.mode) {
                        Mode.LISTENER -> resolve(endpoint.adapter ?: endpoint.host.ifEmpty { "0.0.0.0" }, endpoint.localPort ?: endpoint.port)
                        Mode.RENDEZVOUS -> resolve(endpoint.adapter ?: wildcard(remote!!), endpoint.localPort ?: endpoint.port)
                        Mode.CALLER ->
                            if (endpoint.adapter != null || endpoint.localPort != null) {
                                resolve(endpoint.adapter ?: wildcard(remote!!), endpoint.localPort ?: 0)
                            } else {
                                null
                            }
                    }
                val socket = SrtSocket(endpoint.socketOptions)
                adopt(attempt, socket, listener = endpoint.mode == Mode.LISTENER)
                if (endpoint.mode == Mode.LISTENER) {
                    socket.listen(local!!)
                    synchronized(guard) {
                        checkCurrent(attempt)
                        state.value = ReadyState.LISTENING
                    }
                    val accepted = socket.accept()
                    adopt(attempt, accepted, listener = false)
                    socket.close()
                } else {
                    socket.connect(remote!!, local, rendezvous = endpoint.mode == Mode.RENDEZVOUS)
                }
                synchronized(guard) {
                    checkCurrent(attempt)
                    state.value = ReadyState.OPEN
                    attempt.peer.complete(attempt.socket!!)
                }
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                closeAttempt(attempt, cancelConnect = false)
                throw error
            }
        }

    /**
     * Sends transport bytes as messages of at most 1316 bytes, applying native backpressure.
     * Concurrent calls retain their chunk ordering. Do not mutate [data] until return.
     * Success means libsrt accepted the bytes, not that the peer has consumed them.
     * Cancelling an active send closes the connection; cancelling a queued sender
     * leaves the active sender intact. Empty arrays produce no messages.
     *
     * @param data Transport payload; MPEG-TS framing is the caller's responsibility.
     * @throws IllegalStateException If the connection is not open.
     * @throws java.io.IOException If the peer disconnects or a native send fails.
     * @throws CancellationException If the calling coroutine is cancelled.
     */
    suspend fun send(data: ByteArray) {
        val (attempt, socket) = connected()
        try {
            socket.send(data)
        } finally {
            closeIfSocketClosed(attempt, socket)
        }
    }

    /**
     * Receives one message into an owned byte array. Only one receive or [inputs]
     * collector may be active. Cancellation closes an active receive's connection.
     *
     * @return The next complete SRT message, without media decoding.
     * @throws IllegalStateException If not connected or another reader is active.
     * @throws java.io.IOException If the peer disconnects or receiving fails.
     * @throws CancellationException If the calling coroutine is cancelled.
     */
    suspend fun receive(): ByteArray {
        val (attempt, socket) = connected()
        try {
            return socket.receive()
        } finally {
            closeIfSocketClosed(attempt, socket)
        }
    }

    /**
     * Cancels pending setup and monitoring and releases listener/peer sockets.
     * Idempotent and reusable: a later [connect] starts a new native socket.
     * Queued transport bytes are discarded; close does not flush pending sends.
     * The owning scope is never cancelled by this method.
     */
    override fun close() {
        val attempt = synchronized(guard) { active } ?: return
        closeAttempt(attempt)
    }

    internal fun claimStream(owner: Any) {
        synchronized(guard) {
            check(state.value == ReadyState.OPEN && active?.closing == false) { "Connection is not open" }
            check(streamOwner == null || streamOwner === owner) { "Connection already has an active stream" }
            streamOwner = owner
        }
    }

    internal suspend fun sendStream(
        owner: Any,
        data: ByteArray,
    ) {
        require(data.size % 188 == 0) { "MPEG-TS payload must contain complete packets" }
        val (attempt, socket) = connected()
        synchronized(guard) { check(streamOwner === owner) { "Stream does not own this transport" } }
        // Every native send boundary is also a TS boundary, so stopping media can
        // leave the connection open. A new writer marks its first packets discontinuous.
        try {
            socket.send(data, closeOnCancellation = false)
        } finally {
            closeIfSocketClosed(attempt, socket)
        }
    }

    internal fun releaseStream(owner: Any) {
        synchronized(guard) { if (streamOwner === owner) streamOwner = null }
    }

    internal fun streamInputs(owner: Any): Flow<ByteArray> =
        flow {
            val (attempt, socket) = connected()
            synchronized(guard) { check(streamOwner === owner) { "Stream does not own this transport" } }
            try {
                socket.streamInputs().collect { emit(it) }
            } finally {
                closeIfSocketClosed(attempt, socket)
            }
        }

    private suspend fun resolve(
        host: String,
        port: Int,
    ): InetSocketAddress =
        withContext(Dispatchers.IO) {
            InetSocketAddress(InetAddress.getByName(host), port)
        }

    private fun wildcard(remote: InetSocketAddress): String = if (remote.address is Inet6Address) "::" else "0.0.0.0"

    private fun checkCurrent(attempt: Attempt) {
        if (active !== attempt || attempt.closing) throw CancellationException("SRT connection attempt was closed")
    }

    private fun adopt(
        attempt: Attempt,
        socket: SrtSocket,
        listener: Boolean,
    ) {
        try {
            synchronized(guard) {
                checkCurrent(attempt)
                if (listener) attempt.listener = socket else attempt.socket = socket
            }
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private fun connected(): Pair<Attempt, SrtSocket> =
        synchronized(guard) {
            val attempt = checkNotNull(active) { "Connection is closed" }
            check(!attempt.closing && state.value == ReadyState.OPEN) { "Connection is not open" }
            attempt to checkNotNull(attempt.socket)
        }

    private fun closeIfSocketClosed(
        attempt: Attempt,
        socket: SrtSocket,
    ) {
        if (socket.status == SrtSocket.Status.CLOSED) closeAttempt(attempt)
    }

    private suspend fun samplePerformanceData(
        attempt: Attempt,
        socket: SrtSocket,
    ) {
        while (true) {
            delay(1_000)
            val snapshot =
                try {
                    socket.samplePerformanceData()
                } catch (_: IllegalStateException) {
                    return
                } catch (_: IOException) {
                    continue
                }
            synchronized(guard) {
                if (active !== attempt || attempt.closing) return
                attempt.performanceData = snapshot
            }
        }
    }

    private fun closeAttempt(
        attempt: Attempt,
        cancelConnect: Boolean = true,
    ) {
        val resources =
            synchronized(guard) {
                if (active !== attempt || attempt.closing) return
                attempt.closing = true
                state.value = ReadyState.CLOSING
                listOfNotNull(attempt.socket, attempt.listener)
            }
        if (cancelConnect) attempt.connectJob.cancel()
        attempt.monitor?.cancel()
        try {
            resources.forEach { it.close() }
        } finally {
            synchronized(guard) {
                if (active === attempt) {
                    active = null
                    streamOwner = null
                    state.value = ReadyState.CLOSED
                }
            }
        }
    }

    private class Attempt(val connectJob: Job, val uri: URI, val mode: Mode) {
        val peer = CompletableDeferred<SrtSocket>()
        var socket: SrtSocket? = null
        var listener: SrtSocket? = null
        var monitor: Job? = null
        var performanceData: SrtPerformanceData? = null
        var closing = false
    }

    companion object {
        /** Version of the linked libsrt runtime. Access loads the native library. */
        val version: String
            get() = SrtSocket.version
    }
}
