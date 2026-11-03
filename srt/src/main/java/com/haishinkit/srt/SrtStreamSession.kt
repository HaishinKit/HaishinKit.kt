package com.haishinkit.srt

import android.content.Context
import android.net.Uri
import com.haishinkit.stream.StreamSession
import com.haishinkit.stream.StreamSession.ReadyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** Adapts one reusable SRT transport and media stream to the common session API. */
internal class SrtStreamSession(context: Context, private val uri: Uri, private val mode: StreamSession.Mode) : StreamSession {
    override val stream: SrtStream
        get() = srtStream
    override val isConnected: Boolean
        get() = readyState.value == ReadyState.OPEN && connection.isConnected
    override val readyState
        get() = state.asStateFlow()

    private val guard = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connection = SrtConnection(scope)
    private val srtStream = SrtStream(context.applicationContext, connection)
    private val state = MutableStateFlow(ReadyState.CLOSED)
    private var setup: Job? = null
    private var generation: Job? = null
    private var monitor: Job? = null
    private var closed = CompletableDeferred(Unit)

    override suspend fun connect(): Result<Unit> =
        coroutineScope {
            val job = currentCoroutineContext()[Job]!!
            synchronized(guard) {
                if (state.value != ReadyState.CLOSED) return@coroutineScope Result.failure(IllegalStateException("Session is already active"))
                setup = job
                generation = job
                closed = CompletableDeferred()
                state.value = ReadyState.CONNECTING
            }
            job.invokeOnCompletion { cause ->
                if (cause != null) scope.launch { closeSession(job) }
            }
            try {
                connection.connect(URI(uri.toString()))
                when (mode) {
                    StreamSession.Mode.PUBLISH -> stream.publish()
                    StreamSession.Mode.PLAYBACK -> stream.play()
                }
                synchronized(guard) {
                    check(setup === job && state.value == ReadyState.CONNECTING) { "Session was closed" }
                    setup = null
                    state.value = ReadyState.OPEN
                    monitor =
                        scope.launch {
                            combine(connection.readyState, stream.readyState) { transport, media ->
                                transport == SrtConnection.ReadyState.CLOSED || media == SrtStream.ReadyState.IDLE
                            }.first { it }
                            closeSession(job)
                        }
                }
                Result.success(Unit)
            } catch (cause: Throwable) {
                // A concurrent close owns cleanup until CLOSED, preventing an old setup
                // failure from closing a subsequently reconnected session.
                val ownsCleanup =
                    synchronized(guard) {
                        if (setup !== job || state.value != ReadyState.CONNECTING) {
                            false
                        } else {
                            state.value = ReadyState.CLOSING
                            setup = null
                            true
                        }
                    }
                if (ownsCleanup) cleanup()
                if (cause is CancellationException) throw cause
                Result.failure(cause)
            }
        }

    override suspend fun close(): Result<Unit> = closeSession()

    private suspend fun closeSession(expected: Job? = null): Result<Unit> {
        var pendingSetup: Job? = null
        val completion: CompletableDeferred<Unit>
        val closing =
            synchronized(guard) {
                if (expected != null && generation !== expected) return Result.success(Unit)
                completion = closed
                when (state.value) {
                    ReadyState.CLOSED -> return Result.success(Unit)
                    ReadyState.CLOSING -> false
                    else -> {
                        state.value = ReadyState.CLOSING
                        pendingSetup = setup
                        pendingSetup?.cancel()
                        setup = null
                        monitor?.cancel()
                        monitor = null
                        true
                    }
                }
            }
        if (closing) cleanup(pendingSetup) else withContext(NonCancellable) { completion.await() }
        return Result.success(Unit)
    }

    private suspend fun cleanup(pendingSetup: Job? = null) =
        withContext(NonCancellable) {
            try {
                stream.close()
                connection.close()
                pendingSetup?.join()
                stream.readyState.first { it == SrtStream.ReadyState.IDLE }
            } finally {
                synchronized(guard) {
                    generation = null
                    state.value = ReadyState.CLOSED
                    closed.complete(Unit)
                }
            }
        }
}
