package com.haishinkit.stream

import android.content.Context
import android.net.Uri
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpSession

/**
 * [StreamSession] is a session for live streaming.
 *
 * Streaming with [RtmpConnection] is difficult to use because it requires many idioms.
 * This is a helper class specialized for a one-connection, one-stream setup.
 */
interface StreamSession {
    enum class Method {
        INGEST,
        PLAYBACK,
    }

    /**
     * Helper class for building new [StreamSession].
     */
    class Builder(
        private val applicationContext: Context,
        private val uri: Uri,
    ) {
        fun build(): StreamSession = RtmpSession(applicationContext, uri)
    }

    /**
     * This instance connected to server(true) or not(false).
     */
    val isConnected: Boolean

    /**
     * The stream instance.
     */
    val stream: Stream

    /**
     * Creates a connection to an application on server.
     */
    suspend fun connect(method: Method): Result<Unit>

    /**
     * Closes the connection from the server.
     */
    suspend fun close(): Result<Unit>
}
