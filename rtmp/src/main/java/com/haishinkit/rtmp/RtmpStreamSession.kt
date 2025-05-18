package com.haishinkit.rtmp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.haishinkit.BuildConfig
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.event.EventUtils
import com.haishinkit.rtmp.event.IEventListener
import com.haishinkit.stream.Stream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class RtmpStreamSession(
    applicationContext: Context,
    uri: Uri,
) : StreamSession,
    IEventListener {
    override val stream: Stream
        get() = rtmpStream

    override val isConnected: Boolean
        get() = connection.isConnected

    private val uri = RtmpUri(uri)
    private var method: StreamSession.Method = StreamSession.Method.INGEST
    private val rtmpStream: RtmpStream
    private val connection: RtmpConnection = RtmpConnection()
    private var continuation: CancellableContinuation<Result<Unit>>? = null

    init {
        connection.addEventListener(Event.RTMP_STATUS, this)
        rtmpStream =
            RtmpStream(applicationContext, connection).apply {
                addEventListener(Event.RTMP_STATUS, this@RtmpStreamSession)
            }
    }

    override suspend fun connect(method: StreamSession.Method): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            this.method = method
            this.continuation = continuation
            connection.connect(uri.tcUrl)
        }

    override suspend fun close(): Result<Unit> {
        connection.close()
        return Result.success(Unit)
    }

    override fun handleEvent(event: Event) {
        val data = EventUtils.toMap(event)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "$data")
        }
        when (data["code"]) {
            RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                when (method) {
                    StreamSession.Method.INGEST -> rtmpStream.publish(this@RtmpStreamSession.uri.streamName)
                    StreamSession.Method.PLAYBACK -> rtmpStream.play(this@RtmpStreamSession.uri.streamName)
                }
            }

            RtmpStream.Code.PLAY_START.rawValue, RtmpStream.Code.PUBLISH_START.rawValue -> {
                continuation?.resume(Result.success(Unit))
                continuation = null
            }

            else -> {
                continuation?.resume(Result.failure(RtmpStatusException("${data["code"]}")))
                continuation = null
            }
        }
    }

    companion object {
        private val TAG = RtmpStreamSession::class.java.simpleName
    }
}
