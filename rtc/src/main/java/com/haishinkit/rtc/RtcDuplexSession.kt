package com.haishinkit.rtc

import android.content.Context
import android.media.AudioAttributes
import com.haishinkit.media.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * One audio sendrecv peer and a reliable text data channel. No signaling protocol is assumed.
 * [run] owns the connection until cancellation or failure. The application owns microphone
 * permission, the capture mixer, audio routing, and the coroutine collecting this session.
 * The separate [outgoing] and [incoming] streams never feed decoded audio into capture.
 * Create one instance per call; [run] disposes both streams on exit.
 */
class RtcDuplexSession(context: Context) {
    /** Register with the capture mixer; closing this stream cancels the active call. */
    val outgoing = RtcStream(context.applicationContext) { synchronized(guard) { activeJob?.cancel() } }

    /** Playback stream with communication audio attributes; do not register with capture. */
    val incoming = RtcStream(context.applicationContext) { synchronized(guard) { activeJob?.cancel() } }

    private val guard = Any()
    private var peer: NativePeer? = null
    private var used = false
    private var running = false
    private var disposed = false
    private var activeJob: Job? = null

    init {
        incoming.audioUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION
        outgoing.expectedMedias = setOf(MediaType.AUDIO)
        outgoing.audioSetting.channelCount = 1
    }

    /** Releases streams before starting, or after [run] has finished. Idempotent. */
    fun dispose() {
        synchronized(guard) {
            check(!running) { "Cancel and await run before disposing" }
            disposed = true
        }
        outgoing.dispose()
        incoming.dispose()
    }

    /** Sends text only after the connected callback has run. */
    fun send(text: String) {
        synchronized(guard) { checkNotNull(peer) { "Session is not connected" }.sendText(text) }
    }

    /**
     * [exchange] receives a complete (non-trickle) SDP offer and returns an SDP answer.
     * [onText] executes on the caller's coroutine context and must not block.
     * Returns only on cancellation or failure; no automatic reconnect is performed.
     */
    suspend fun run(
        dataChannelLabel: String,
        iceServers: List<String> = emptyList(),
        timeoutMillis: Long = 30_000,
        exchange: suspend (String) -> String,
        onText: (String) -> Unit,
        onConnected: () -> Unit,
    ) {
        require(dataChannelLabel.isNotBlank())
        require(timeoutMillis > 0)
        synchronized(guard) {
            check(!used && !disposed) { "Create a new duplex session for each call" }
            used = true
            running = true
        }
        var native: NativePeer? = null
        try {
            coroutineScope {
                val runJob = currentCoroutineContext()[Job]
                synchronized(guard) { activeJob = runJob }
                val events = Channel<Event>(64)
                val overflow = CompletableDeferred<Throwable>()
                val gathered = CompletableDeferred<String>()
                val audioOpen = CompletableDeferred<Unit>()
                val dataOpen = CompletableDeferred<Unit>()
                val connected = CompletableDeferred<Unit>()
                launch { throw overflow.await() }
                launch {
                    for (event in events) {
                        when (event.kind) {
                            NativePeer.SDP -> gathered.complete(event.text)
                            NativePeer.TRACK_OPEN -> audioOpen.complete(Unit)
                            NativePeer.DATA_OPEN -> dataOpen.complete(Unit)
                            NativePeer.DATA_TEXT -> onText(event.text)
                            NativePeer.ERROR -> throw IOException(event.text)
                            NativePeer.STATE ->
                                when (event.value) {
                                    2 -> connected.complete(Unit)
                                    in 3..5 -> throw IOException("RTC transport disconnected (${event.value})")
                                }
                        }
                    }
                }
                val created =
                    NativePeer(true, true, false, iceServers.toTypedArray(), { kind, value, text ->
                        if (events.trySend(Event(kind, value, text)).isFailure) {
                            overflow.complete(IOException("RTC event queue capacity exceeded"))
                        }
                    }, { audio, data, timestamp -> incoming.receive(audio, data, timestamp) }, true, dataChannelLabel)
                native = created
                withTimeout(timeoutMillis) {
                    created.offer()
                    val answer = exchange(gathered.await())
                    // Validate that Opus audio was accepted. The server must also accept sendrecv.
                    Sdp.validateAnswer(answer, false, true, false, duplex = true)
                    created.answer(answer)
                    connected.await()
                    audioOpen.await()
                    dataOpen.await()
                }
                val sendReady = CompletableDeferred<Unit>()
                val receiveReady = CompletableDeferred<Unit>()
                launch(start = CoroutineStart.UNDISPATCHED) {
                    incoming.execute(false, setOf("audio"), created, emptyList()) { receiveReady.complete(Unit) }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    outgoing.execute(true, setOf("audio"), created, emptyList()) { sendReady.complete(Unit) }
                }
                sendReady.await()
                receiveReady.await()
                synchronized(guard) { peer = created }
                onConnected()
                awaitCancellation()
            }
        } finally {
            withContext(NonCancellable) {
                synchronized(guard) {
                    peer = null
                    activeJob = null
                    running = false
                    disposed = true
                }
                try {
                    native?.close()
                } finally {
                    outgoing.dispose()
                    incoming.dispose()
                }
            }
        }
    }

    private data class Event(val kind: Int, val value: Int, val text: String)
}
