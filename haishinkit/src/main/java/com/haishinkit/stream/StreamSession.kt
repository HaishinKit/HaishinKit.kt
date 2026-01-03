package com.haishinkit.stream

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * [StreamSession] is a session for live streaming.
 *
 * Streaming with [com.haishinkit.rtmp.RtmpConnection] is difficult to use because it requires many idioms.
 * This is a helper class specialized for a one-connection, one-stream setup.
 */
interface StreamSession {
    /**
     * Represents the type of session to establish.
     */
    enum class Mode {
        /**
         * A publishing session, used to stream media from the local device to a server or peers.
         */
        PUBLISH,

        /**
         * A playback session, used to receive and play media streamed from a server or peers.
         */
        PLAYBACK,
    }

    /**
     * Represents the current connection state of a session.
     */
    enum class ReadyState {
        /**
         * The session is currently attempting to establish a connection.
         */
        CONNECTING,

        /**
         * The session has been successfully established and is ready for communication.
         */
        OPEN,

        /**
         * The session is in the process of closing the connection.
         */
        CLOSING,

        /**
         * The session has been closed or could not be established.
         */
        CLOSED,
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

        private var mode: Mode = Mode.PUBLISH

        /**
         * Sets a publish or playback mode.
         */
        fun setMode(mode: Mode): Builder {
            this.mode = mode
            return this
        }

        fun build(): StreamSession {
            val scheme = uri.scheme
            for (factory in factoryMap) {
                if (factory.key == scheme) {
                    return factory.value.create(context, uri, mode)
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
     * The current readyState.
     */
    val readyState: StateFlow<ReadyState>

    /**
     * The stream instance.
     */
    val stream: Stream

    /**
     * Creates a connection to an application on server.
     */
    suspend fun connect(): Result<Unit>

    /**
     * Closes the connection from the server.
     */
    suspend fun close(): Result<Unit>
}
