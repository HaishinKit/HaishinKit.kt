package com.haishinkit.stream

import android.content.Context
import android.net.Uri

/**
 * [StreamSession] is a session for live streaming.
 *
 * Streaming with [com.haishinkit.rtmp.RtmpConnection] is difficult to use because it requires many idioms.
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
        private val context: Context,
        private val uri: Uri,
    ) {
        companion object {
            private var factoryMap = mutableMapOf<String, StreamSessionFactory>()

            /**
             * Registers a stream session factory.
             */
            fun registerFactory(factory: StreamSessionFactory) {
                factory.protocols.forEach {
                    factoryMap[it] = factory
                }
            }
        }

        fun build(): StreamSession {
            val scheme = uri.scheme
            for (factory in factoryMap) {
                if (factory.key == scheme) {
                    return factory.value.create(context, uri)
                }
            }
            throw NullPointerException()
        }
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
