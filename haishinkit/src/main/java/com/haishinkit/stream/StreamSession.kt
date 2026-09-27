package com.haishinkit.stream

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages a single connection and stream for publishing or playback.
 *
 * Register a protocol-specific [StreamSessionFactory] with [Builder.registerFactory], then
 * create a session with [Builder]. Observe [readyState] for connection state changes.
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
     * Builds a session using a factory registered for the URI scheme.
     *
     * @param context The Android context used to create the session.
     * @param uri The complete stream URI, including the stream name.
     */
    class Builder(
        private val context: Context,
        private val uri: Uri,
    ) {
        companion object {
            private var factoryMap = mutableMapOf<String, StreamSessionFactory>()

            /**
             * Registers a factory for its supported URI schemes, replacing any previous factory for those schemes.
             */
            fun registerFactory(factory: StreamSessionFactory) {
                factory.protocols.forEach {
                    factoryMap[it] = factory
                }
            }
        }

        private var mode: Mode = Mode.PUBLISH

        /**
         * Sets the session mode and returns this builder. The default is [Mode.PUBLISH].
         */
        fun setMode(mode: Mode): Builder {
            this.mode = mode
            return this
        }

        /**
         * Creates a session without connecting it.
         *
         * @throws NullPointerException If no factory is registered for the URI scheme.
         */
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
     * Whether the underlying connection is connected to the server.
     */
    val isConnected: Boolean

    /**
     * Observable connection state for this session.
     */
    val readyState: StateFlow<ReadyState>

    /**
     * The stream used to configure codecs and register media inputs or outputs.
     */
    val stream: Stream

    /**
     * Connects to the server and starts publishing or playback in the configured mode.
     *
     * @return Success when the session starts, or a failure describing the connection error.
     */
    suspend fun connect(): Result<Unit>

    /**
     * Closes the connection and its stream.
     *
     * @return The result of closing the session.
     */
    suspend fun close(): Result<Unit>
}
