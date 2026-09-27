package com.haishinkit.net

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * A two-way TCP transport with optional TLS and asynchronous input callbacks.
 */
interface NetSocket {
    /**
     * Receives connection, input, and error notifications from the transport.
     */
    interface Listener {
        /**
         * Receives incoming bytes. The transport may reuse the buffer after the callback returns.
         */
        fun onInput(buffer: ByteBuffer)

        /**
         * Reports a socket connection error.
         */
        fun onSocketError()

        /**
         * Reports that the socket connection has been established.
         */
        fun onConnect()

        /**
         * Reports socket closure with the disconnection flag supplied by the transport.
         */
        fun onClose(disconnected: Boolean)
    }

    /**
     * The transport timeout setting. [NetSocketImpl] currently stores this value without applying it to the socket.
     */
    var timeout: Int

    /**
     * The listener for socket events, or `null`.
     */
    var listener: Listener?

    /**
     * The total number of bytes received during the current connection.
     */
    val totalBytesIn: AtomicLong

    /**
     * The total number of bytes sent during the current connection.
     */
    val totalBytesOut: AtomicLong

    /**
     * The number of outgoing bytes currently queued for transmission.
     */
    val queueBytesOut: AtomicLong

    /**
     * Starts a connection to the destination.
     *
     * @param dstName The destination host name.
     * @param dstPort The destination port.
     * @param isSecure Whether to use TLS.
     */
    fun connect(
        dstName: String,
        dstPort: Int,
        isSecure: Boolean,
    )

    /**
     * Queues a buffer for transmission. [NetSocketImpl] flips the buffer before sending its contents.
     */
    fun doOutput(buffer: ByteBuffer)

    /**
     * Closes the socket and forwards [disconnected] to [Listener.onClose].
     */
    fun close(disconnected: Boolean)

    /**
     * Obtains a writable buffer with the requested capacity in bytes, potentially reusing pooled storage.
     */
    fun createByteBuffer(capacity: Int): ByteBuffer
}
