package com.haishinkit.rtmp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.event.EventUtils
import com.haishinkit.rtmp.event.IEventListener
import com.haishinkit.stream.Stream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class RtmpStreamSession(
    applicationContext: Context,
    uri: Uri,
    val mode: StreamSession.Mode,
) : StreamSession,
    IEventListener {
    override val stream: Stream
        get() = rtmpStream

    override val isConnected: Boolean
        get() = connection.isConnected

    private val _readyState = MutableStateFlow(StreamSession.ReadyState.CLOSED)
    override var readyState: StateFlow<StreamSession.ReadyState> = _readyState

    private val uri = RtmpUri(uri)
    private val rtmpStream: RtmpStream
    private val connection: RtmpConnection = RtmpConnection()
    private var continuation: CancellableContinuation<Result<Unit>>? = null

    init {
        connection.apply {
            addEventListener(Event.RTMP_STATUS, this@RtmpStreamSession)
            addEventListener(Event.IO_ERROR, this@RtmpStreamSession)
        }
        rtmpStream =
            RtmpStream(applicationContext, connection).apply {
                addEventListener(Event.RTMP_STATUS, this@RtmpStreamSession)
            }
    }

    override suspend fun connect(): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            _readyState.value = StreamSession.ReadyState.CONNECTING
            this.continuation = continuation
            connection.connect(uri.tcUrl)
        }

    override suspend fun close(): Result<Unit> {
        if (connection.isConnected) {
            return Result.failure(IllegalStateException())
        }
        _readyState.value = StreamSession.ReadyState.CLOSING
        connection.close()
        _readyState.value = StreamSession.ReadyState.CLOSED
        return Result.success(Unit)
    }

    override fun handleEvent(event: Event) {
        val data = EventUtils.toMap(event)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "$data")
        }

        when (event.type) {
            Event.IO_ERROR -> {
                continuation?.resume(Result.failure(RtmpStatusException("")))
                continuation = null
                _readyState.value = StreamSession.ReadyState.CLOSED
                return
            }
            else -> {
                // no op
            }
        }

        when (data["code"]) {
            RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                when (mode) {
                    StreamSession.Mode.PUBLISH -> rtmpStream.publish(this@RtmpStreamSession.uri.streamName)
                    StreamSession.Mode.PLAYBACK -> rtmpStream.play(this@RtmpStreamSession.uri.streamName)
                }
            }

            RtmpStream.Code.PLAY_START.rawValue, RtmpStream.Code.PUBLISH_START.rawValue -> {
                continuation?.resume(Result.success(Unit))
                continuation = null
                _readyState.value = StreamSession.ReadyState.OPEN
            }

            else -> {
                continuation?.resume(Result.failure(RtmpStatusException("${data["code"]}")))
                continuation = null
                _readyState.value = StreamSession.ReadyState.CLOSED
            }
        }
    }

    companion object {
        private val TAG = RtmpStreamSession::class.java.simpleName
    }
}
