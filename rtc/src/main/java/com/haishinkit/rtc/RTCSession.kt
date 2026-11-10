package com.haishinkit.rtc

import android.content.Context
import android.net.Uri
import com.haishinkit.media.MediaType
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.URI

/**
 * WHIP publishing or WHEP playback over HTTP(S), using a non-trickle ICE offer.
 * A session owns one reusable stream. Register its stream with a mixer for publishing
 * or attach a StreamView for playback. Close before reconnecting. Configure settings
 * while closed. HTTP requests use finite connect/read timeouts, including on cancellation.
 */
class RTCSession(
    context: Context,
    private val uri: Uri,
    private val mode: StreamSession.Mode = StreamSession.Mode.PUBLISH,
    private val configuration: RTCConfiguration = RTCConfiguration(),
) : StreamSession {
    override val stream = RTCStream(context.applicationContext) { synchronized(guard) { current?.job?.cancel() } }
    override val readyState: StateFlow<StreamSession.ReadyState>
        get() = state.asStateFlow()
    override val isConnected: Boolean
        get() = state.value == StreamSession.ReadyState.OPEN

    /** Most recent asynchronous transport/media/cleanup failure; reset on connect. */
    val failure: StateFlow<Throwable?>
        get() = error.asStateFlow()

    private val guard = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(StreamSession.ReadyState.CLOSED)
    private val error = MutableStateFlow<Throwable?>(null)
    private var current: Run? = null

    override suspend fun connect(): Result<Unit> {
        val run =
            synchronized(guard) {
                if (current != null) return Result.failure(IllegalStateException("RTC session is already active"))
                val created = Run()
                created.job = scope.launch(start = CoroutineStart.LAZY) { execute(created) }
                created.job.invokeOnCompletion { cause ->
                    created.started.completeExceptionally(cause ?: CancellationException("RTC session closed"))
                    synchronized(guard) {
                        if (current === created) {
                            current = null
                            state.value = StreamSession.ReadyState.CLOSED
                        }
                    }
                }
                current = created
                error.value = null
                state.value = StreamSession.ReadyState.CONNECTING
                created
            }
        run.job.start()
        return try {
            run.started.await()
            Result.success(Unit)
        } catch (cause: Throwable) {
            withContext(NonCancellable) { run.job.cancelAndJoin() }
            if (cause is CancellationException) throw cause
            Result.failure(cause)
        }
    }

    override suspend fun close(): Result<Unit> {
        val run =
            synchronized(guard) {
                current?.also { state.value = StreamSession.ReadyState.CLOSING }
            } ?: return Result.success(Unit)
        withContext(NonCancellable) { run.job.cancelAndJoin() }
        return run.cleanupFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private suspend fun execute(run: Run) {
        val http = HTTPTransport(configuration)
        var resource: HTTPTransport.Resource? = null
        var peer: NativePeer? = null
        try {
            coroutineScope {
                val publishing = mode == StreamSession.Mode.PUBLISH
                val medias = if (publishing) stream.offeredMedias() else setOf(MediaType.AUDIO, MediaType.VIDEO)
                val audio = MediaType.AUDIO in medias
                val video = MediaType.VIDEO in medias
                val gathered = CompletableDeferred<String>()
                val opened = MutableStateFlow(emptySet<String>())
                val events = Channel<Event>(64)
                val overflow = CompletableDeferred<Throwable>()
                launch { throw overflow.await() }
                launch {
                    for (event in events) {
                        when (event.kind) {
                            NativePeer.SDP -> gathered.complete(event.text)
                            NativePeer.STATE -> if (event.value in 3..5) throw IOException("RTC transport disconnected (state ${event.value})")
                            NativePeer.ERROR -> throw IOException(event.text)
                            NativePeer.TRACK_OPEN -> opened.value += if (event.value == 1) "audio" else "video"
                            NativePeer.KEYFRAME -> stream.requestKeyframe()
                            NativePeer.BITRATE -> stream.requestBitrate(event.value)
                        }
                    }
                }
                val native =
                    NativePeer(publishing, audio, video, configuration.iceServers.toTypedArray(), { kind, value, text ->
                        if (events.trySend(Event(kind, value, text)).isFailure) overflow.complete(IOException("RTC event queue capacity exceeded"))
                    }, stream::receive)
                peer = native
                var videoConfiguration = emptyList<ByteArray>()
                val tracks =
                    withTimeout(configuration.connectionTimeoutMillis) {
                        native.offer()
                        val offer = gathered.await()
                        val response =
                            withContext(Dispatchers.IO) {
                                http.create(URI(uri.toString()), offer) { resource = it }
                            }
                        val selected = SDP.validateAnswer(response.answer, publishing, audio, video)
                        videoConfiguration = SDP.videoConfiguration(response.answer)
                        native.answer(response.answer)
                        opened.first { it.containsAll(selected) }
                        selected
                    }
                stream.execute(publishing, tracks, native, videoConfiguration) {
                    synchronized(guard) {
                        check(current === run && state.value == StreamSession.ReadyState.CONNECTING && run.job.isActive) { "Session was closed" }
                        state.value = StreamSession.ReadyState.OPEN
                        run.started.complete(Unit)
                    }
                }
            }
        } catch (cause: Throwable) {
            if (cause !is CancellationException) error.value = cause
            run.started.completeExceptionally(cause)
        } finally {
            withContext(NonCancellable) {
                synchronized(guard) { state.value = StreamSession.ReadyState.CLOSING }
                try {
                    peer?.close()
                } catch (cause: Throwable) {
                    run.cleanupFailure = cause
                    error.value = cause
                }
                try {
                    resource?.let { withContext(Dispatchers.IO) { http.delete(it) } }
                } catch (cause: Throwable) {
                    run.cleanupFailure = cause
                    error.value = cause
                } finally {
                    synchronized(guard) {
                        if (current === run) {
                            current = null
                            state.value = StreamSession.ReadyState.CLOSED
                        }
                    }
                }
            }
        }
    }

    private data class Event(val kind: Int, val value: Int, val text: String)

    private class Run {
        val started = CompletableDeferred<Unit>()
        var cleanupFailure: Throwable? = null
        lateinit var job: Job
    }
}
