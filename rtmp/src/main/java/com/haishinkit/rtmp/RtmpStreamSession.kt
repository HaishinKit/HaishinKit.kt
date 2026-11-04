package com.haishinkit.rtmp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.event.EventUtils
import com.haishinkit.rtmp.event.IEventListener
import com.haishinkit.stream.Stream
import com.haishinkit.stream.StreamSession
import com.haishinkit.stream.StreamSession.ReadyState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class RtmpStreamSession(applicationContext: Context, uri: Uri, val mode: StreamSession.Mode) : StreamSession, IEventListener {
    override val stream: Stream get() = rtmpStream
    override val isConnected: Boolean get() = connection.isConnected
    override val readyState: StateFlow<ReadyState> get() = state.asStateFlow()

    private val guard = Any()
    private val state = MutableStateFlow(ReadyState.CLOSED)
    private val uri = RtmpUri(uri)
    private val connection = RtmpConnection()
    private val rtmpStream: RtmpStream by lazy {
        RtmpStream(applicationContext, connection).apply {
            addEventListener(Event.RTMP_STATUS, this@RtmpStreamSession)
        }
    }
    private var continuation: CancellableContinuation<Result<Unit>>? = null

    init {
        connection.addEventListener(Event.RTMP_STATUS, this)
        connection.addEventListener(Event.IO_ERROR, this)
    }

    override suspend fun connect(): Result<Unit> =
        suspendCancellableCoroutine { pending ->
            val accepted =
                synchronized(guard) {
                    if (connection.isConnected || state.value != ReadyState.CLOSED) {
                        false
                    } else {
                        continuation = pending
                        state.value = ReadyState.CONNECTING
                        connection.connect(uri.tcUrl)
                        true
                    }
                }
            if (!accepted) pending.resume(Result.failure(IllegalStateException("Session is already active")))
        }

    override suspend fun close(): Result<Unit> {
        val pending =
            synchronized(guard) {
                state.value = ReadyState.CLOSING
                continuation.also { continuation = null }
            }
        try {
            connection.close()
            connection.socket.closeAndJoin()
        } finally {
            synchronized(guard) { state.value = ReadyState.CLOSED }
            if (pending?.isActive == true) pending.resume(Result.failure(RtmpStatusException("Session closed")))
        }
        return Result.success(Unit)
    }

    override fun handleEvent(event: Event) {
        val data = EventUtils.toMap(event)
        if (BuildConfig.DEBUG) Log.d(TAG, "$data")
        if (event.type == Event.IO_ERROR) {
            fail("")
            return
        }
        if (event.type != Event.RTMP_STATUS) return
        val code = data["code"] as? String
        if (code == RtmpConnection.Code.CONNECT_SUCCESS.rawValue) {
            if (state.value != ReadyState.CONNECTING) return
            when (mode) {
                StreamSession.Mode.PUBLISH -> rtmpStream.publish(uri.streamName)
                StreamSession.Mode.PLAYBACK -> rtmpStream.play(uri.streamName)
            }
            return
        }
        val transition =
            synchronized(guard) {
                val next = RtmpSessionStatus.nextState(state.value, code, data["level"] as? String)
                if (next == state.value) return
                val closing = next == ReadyState.CLOSED
                state.value = if (closing) ReadyState.CLOSING else next
                closing to continuation.also { continuation = null }
            }
        if (transition.first) {
            finishClose(transition.second, code.orEmpty())
        } else {
            transition.second?.let { if (it.isActive) it.resume(Result.success(Unit)) }
        }
    }

    private fun fail(code: String) {
        val pending =
            synchronized(guard) {
                if (state.value == ReadyState.CLOSED || state.value == ReadyState.CLOSING) return
                state.value = ReadyState.CLOSING
                continuation.also { continuation = null }
            }
        finishClose(pending, code)
    }

    private fun finishClose(
        pending: CancellableContinuation<Result<Unit>>?,
        code: String,
    ) {
        try {
            connection.close()
        } finally {
            synchronized(guard) { state.value = ReadyState.CLOSED }
            if (pending?.isActive == true) pending.resume(Result.failure(RtmpStatusException(code)))
        }
    }

    private companion object {
        val TAG = RtmpStreamSession::class.java.simpleName
    }
}
