package com.haishinkit.rtmp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.haishinkit.BuildConfig
import com.haishinkit.event.Event
import com.haishinkit.event.EventUtils
import com.haishinkit.event.IEventListener
import com.haishinkit.stream.Stream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class RtmpSession(
    private val applicationContext: Context,
    uri: Uri,
) : StreamSession {
    override val stream: Stream
        get() = rtmpStream

    override val isConnected: Boolean
        get() = connection.isConnected

    private val rtmpStream: RtmpStream
    private val listener =
        object : IEventListener {
            override fun handleEvent(event: Event) {
                val data = EventUtils.toMap(event)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "$data['code']")
                }
                when (data["code"]) {
                    RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                        when (method) {
                            StreamSession.Method.INGEST -> rtmpStream.publish(this@RtmpSession.uri.streamName)
                            StreamSession.Method.PLAYBACK -> rtmpStream.play(this@RtmpSession.uri.streamName)
                        }
                        continuation?.resume(Unit)
                    }

                    else -> {
                        continuation?.resume(Unit)
                    }
                }
            }
        }

    private val uri = RtmpUri(uri)
    private var method: StreamSession.Method = StreamSession.Method.INGEST
    private val connection: RtmpConnection = RtmpConnection()
    private var continuation: CancellableContinuation<Unit>? = null

    init {
        connection.addEventListener(Event.RTMP_STATUS, listener)
        rtmpStream = RtmpStream(applicationContext = applicationContext, connection = connection)
    }

    override suspend fun connect(method: StreamSession.Method): Unit =
        suspendCancellableCoroutine { continuation ->
            this.method = method
            this.continuation = continuation
            connection.connect(uri.tcUrl)
        }

    override suspend fun close() {
        connection.close()
    }

    companion object {
        private val TAG = RtmpSession::class.java.simpleName
    }
}
