package com.haishinkit.net

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * The NetSocket interface establish a two-way TCP/IP socket connections.
 */
interface NetSocket {
    /**
     * The Listener interface is the primary method for handling events.
     */
    interface Listener {
        fun onInput(buffer: ByteBuffer)

        fun onSocketError()

        fun onConnect()

        fun onClose(disconnected: Boolean)
    }

    /**
     * Specifies the timeout indicates time to wait for TCP/IP Handshake done.
     */
    var timeout: Int

    /**
     * Specifies the listener indicates the [NetSocket.Listener] are currently being evaluated.
     */
    var listener: Listener?

    /**
     * The totalBytesIn indicates statistics of total incoming bytes.
     */
    val totalBytesIn: AtomicLong

    /**
     * The totalBytesOut indicates statistics of total outgoing bytes.
     */
    val totalBytesOut: AtomicLong

    /**
     * The queueBytesOut indicates statistics of total current queueing bytes.
     */
    val queueBytesOut: AtomicLong

    /**
     * Creates a two-way connection to an application server.
     */
    fun connect(
        dstName: String,
        dstPort: Int,
        isSecure: Boolean,
    )

    /**
     * Do output a butter to an application server.
     */
    fun doOutput(buffer: ByteBuffer)

    /**
     * Closes a two-way connection to an application server.
     */
    fun close(disconnected: Boolean)

    /**
     * Creates a [ByteBuffer] for memory management.
     */
    fun createByteBuffer(capacity: Int): ByteBuffer
}
