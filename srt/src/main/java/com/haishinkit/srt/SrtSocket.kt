package com.haishinkit.srt

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.net.Inet6Address
import java.net.InetSocketAddress
import kotlin.coroutines.resume

/**
 * Coroutine adapter for the native SrtSocket. C++ owns socket state, configuration,
 * reader/send ownership and resource lifetime; epoll notifications resume coroutine waits.
 * Operations run in the caller's coroutine. Close is immediate and idempotent.
 */
internal class SrtSocket private constructor(handle: Long, timeoutMs: Int) : Closeable {
    class Options(
        val latencyMs: Int = 120,
        val connectTimeoutMs: Int = 3000,
        val passphrase: String? = null,
        val streamId: String? = null,
    )

    enum class Status {
        UNKNOWN,
        INIT,
        OPENED,
        LISTENING,
        CONNECTING,
        CONNECTED,
        BROKEN,
        CLOSING,
        CLOSED,
        NONEXIST,
    }

    val status: Status
        get() = Status.entries.getOrElse(nativeStatus(handle)) { Status.UNKNOWN }

    val localAddress: InetSocketAddress
        get() = nativeLocalAddress(handle)

    /** One collecting coroutine owns receive until collection ends, which closes the socket. */
    val inputs: Flow<ByteArray>
        get() =
            flow {
                withReader {
                    try {
                        while (true) emit(awaitValue(READ_READY) { nativeReceive(handle) })
                    } finally {
                        close()
                    }
                }
            }

    private val handle = handle
    private val timeoutMs = timeoutMs

    constructor(options: Options = Options()) : this(create(options), options.connectTimeoutMs)

    /** Connect as a caller, or bind locally and connect in rendezvous mode. */
    suspend fun connect(
        remote: InetSocketAddress,
        local: InetSocketAddress? = null,
        rendezvous: Boolean = false,
    ) {
        validateAddress(remote)
        local?.let { validateAddress(it) }
        currentCoroutineContext().ensureActive()
        // Native setup rejects duplicate opens without disturbing the existing operation.
        nativeConnect(handle, remote.address.address, remote.port, local?.address?.address, local?.port ?: 0, rendezvous)
        closeOnFailure {
            withTimeout(timeoutMs.toLong()) { awaitValue(WRITE_READY) { nativePollConnect(handle).takeIf { it } } }
        }
    }

    fun listen(local: InetSocketAddress) {
        validateAddress(local)
        nativeListen(handle, local.address.address, local.port)
    }

    suspend fun accept(): SrtSocket =
        withReader(accepting = true) {
            closeOnFailure {
                val accepted = awaitValue(READ_READY) { nativeAccept(handle).takeIf { it != 0L } }
                try {
                    SrtSocket(accepted, timeoutMs)
                } catch (error: Throwable) {
                    nativeClose(accepted)
                    throw error
                }
            }
        }

    /** C++ fragments messages and applies backpressure; the caller retains the array until return. */
    suspend fun send(
        data: ByteArray,
        closeOnCancellation: Boolean = true,
    ) {
        // Cancellation while waiting for another sender must not close its socket.
        awaitValue(SEND_AVAILABLE) { nativeTryBeginSend(handle).takeIf { it } }
        try {
            closeOnFailure(closeOnCancellation) {
                var offset = 0
                while (offset < data.size) {
                    currentCoroutineContext().ensureActive()
                    val sent = nativeSend(handle, data, offset)
                    offset += sent
                    if (sent == 0) awaitReady(WRITE_READY)
                }
            }
        } finally {
            nativeEndSend(handle)
        }
    }

    suspend fun receive(): ByteArray =
        withReader {
            closeOnFailure { awaitValue(READ_READY) { nativeReceive(handle) } }
        }

    fun streamInputs() =
        flow {
            withReader {
                closeOnFailure(closeOnCancellation = false) {
                    while (true) emit(awaitValue(READ_READY) { nativeReceive(handle) })
                }
            }
        }

    /** Returns a fresh native snapshot without resetting the statistics interval. */
    fun performanceData(): SrtPerformanceData = getPerformanceData(resetInterval = false)

    internal fun getPerformanceData(resetInterval: Boolean): SrtPerformanceData = nativePerformanceData(handle, resetInterval)

    /** Releases the native owner; packets queued in libsrt are not guaranteed to be delivered. */
    override fun close() = nativeClose(handle)

    internal suspend fun awaitDisconnected() {
        awaitValue(ERROR_READY) { status.takeIf { it != Status.CONNECTED } }
    }

    private suspend fun <T : Any> awaitValue(
        events: Int,
        read: () -> T?,
    ): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            read()?.let { return it }
            awaitReady(events)
        }
    }

    private suspend fun awaitReady(events: Int) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val token = nativeAwait(handle, events, EventWaiter(continuation))
            continuation.invokeOnCancellation { nativeCancelAwait(handle, token) }
        }

    private suspend fun <T> withReader(
        accepting: Boolean = false,
        block: suspend () -> T,
    ): T {
        nativeBeginRead(handle, accepting)
        try {
            return block()
        } finally {
            nativeEndRead(handle)
        }
    }

    private suspend fun <T> closeOnFailure(
        closeOnCancellation: Boolean = true,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (error: Throwable) {
            if (error !is kotlinx.coroutines.CancellationException || closeOnCancellation) close()
            throw error
        }

    private external fun nativeStatus(context: Long): Int

    private external fun nativeLocalAddress(context: Long): InetSocketAddress

    private external fun nativeConnect(
        context: Long,
        remote: ByteArray,
        remotePort: Int,
        local: ByteArray?,
        localPort: Int,
        rendezvous: Boolean,
    )

    private external fun nativePollConnect(context: Long): Boolean

    private external fun nativeListen(
        context: Long,
        local: ByteArray,
        port: Int,
    )

    private external fun nativeBeginRead(
        context: Long,
        accepting: Boolean,
    )

    private external fun nativeEndRead(context: Long)

    private external fun nativeAccept(context: Long): Long

    private external fun nativeTryBeginSend(context: Long): Boolean

    private external fun nativeEndSend(context: Long)

    private external fun nativeSend(
        context: Long,
        data: ByteArray,
        offset: Int,
    ): Int

    private external fun nativeReceive(context: Long): ByteArray?

    private external fun nativePerformanceData(
        context: Long,
        resetInterval: Boolean,
    ): SrtPerformanceData

    private external fun nativeClose(context: Long)

    private external fun nativeAwait(
        context: Long,
        events: Int,
        waiter: EventWaiter,
    ): Long

    private external fun nativeCancelAwait(
        context: Long,
        token: Long,
    )

    private class EventWaiter(private val continuation: CancellableContinuation<Unit>) {
        // Native registration is one-shot. Resuming a cancelled continuation is safe.
        fun onReady() = continuation.resume(Unit)
    }

    companion object {
        val version: String
            get() = nativeVersion()
        const val PAYLOAD_SIZE = 1316
        private const val READ_READY = 1
        private const val WRITE_READY = 4
        private const val SEND_AVAILABLE = 0
        private const val ERROR_READY = 8

        init {
            System.loadLibrary("srt")
        }

        private fun create(options: Options): Long =
            nativeCreate(
                options.latencyMs,
                options.connectTimeoutMs,
                options.passphrase?.toByteArray(Charsets.UTF_8),
                options.streamId?.toByteArray(Charsets.UTF_8),
            )

        private fun validateAddress(address: InetSocketAddress) {
            require(!address.isUnresolved) { "Socket address must be resolved" }
            val ip = address.address
            require(ip !is Inet6Address || ip.scopeId == 0) { "Scoped IPv6 addresses are not supported yet" }
        }

        @JvmStatic
        private external fun nativeVersion(): String

        @JvmStatic
        private external fun nativeCreate(
            latencyMs: Int,
            timeoutMs: Int,
            passphrase: ByteArray?,
            streamId: ByteArray?,
        ): Long
    }
}
