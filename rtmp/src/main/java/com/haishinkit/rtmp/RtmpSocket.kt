package com.haishinkit.rtmp

import android.util.Log
import com.haishinkit.net.NetSocket
import com.haishinkit.net.NetSocketImpl
import com.haishinkit.rtmp.event.Event
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

internal class RtmpSocket(
    val connection: RtmpConnection,
) {
    enum class ReadyState {
        Uninitialized,
        VersionSent,
        AckSent,
        HandshakeDone,
        Closing,
        Closed,
    }

    var bandWidth = 0
        internal set
    var chunkSizeC = RtmpChunk.DEFAULT_SIZE
    var chunkSizeS = RtmpChunk.DEFAULT_SIZE
    var isConnected = false
        private set
    var timeout: Int
        get() = socket?.timeout ?: 0
        set(value) {
            socket?.timeout = value
        }
    val totalBytesIn: Long
        get() = socket?.totalBytesIn?.get() ?: 0
    val totalBytesOut: Long
        get() = socket?.totalBytesOut?.get() ?: 0

    @Volatile
    var handshakeDurationMillis: Long? = null
        private set

    private var handshakeStartedAtNanos = 0L

    @Volatile
    private var socket: NetSocket? = null
    private var handshake = RtmpHandshake()

    @Volatile
    private var readyState = ReadyState.Uninitialized
        set(value) {
            field = value
            connection.onSocketReadyStateChange(this, value)
        }

    @Synchronized
    fun connect(
        dstName: String,
        dstPort: Int,
        isSecure: Boolean,
        transport: NetSocket = NetSocketImpl(),
    ) {
        val previousTransport = socket
        socket = transport
        handshakeDurationMillis = null
        isConnected = false
        readyState = ReadyState.Uninitialized
        previousTransport?.listener = null
        previousTransport?.close(false)
        transport.listener =
            object : NetSocket.Listener {
                override fun onConnect() = this@RtmpSocket.onConnect(transport)

                override fun onInput(buffer: ByteBuffer) = this@RtmpSocket.onInput(transport, buffer)

                override fun onClose(disconnected: Boolean) {
                    closeTransport(transport, disconnected)
                }

                override fun onSocketError() {
                    if (closeTransport(transport, false)) {
                        connection.dispatchEventWith(Event.IO_ERROR, false)
                        Log.i(TAG, "a connection was failed")
                    }
                }
            }
        transport.connect(dstName, dstPort, isSecure)
    }

    fun doOutput(buffer: ByteBuffer) {
        socket?.doOutput(buffer)
    }

    fun createByteBuffer(capacity: Int): ByteBuffer = socket?.createByteBuffer(capacity) ?: ByteBuffer.allocate(capacity)

    fun close(disconnected: Boolean) {
        closeTransport(socket, disconnected)
    }

    suspend fun closeAndJoin() {
        val transport = socket
        closeTransport(transport, false)
        (transport as? NetSocketImpl)?.awaitClosed()
    }

    private fun closeTransport(
        transport: NetSocket?,
        disconnected: Boolean,
    ): Boolean {
        val code =
            synchronized(this) {
                if (socket !== transport || readyState == ReadyState.Closing || readyState == ReadyState.Closed) return false
                val handshakeDone = readyState == ReadyState.HandshakeDone
                readyState = ReadyState.Closing
                transport?.listener = null
                if (handshakeDone) RtmpConnection.Code.CONNECT_CLOSED else RtmpConnection.Code.CONNECT_FAILED
            }
        transport?.close(disconnected)
        if (disconnected) {
            connection.dispatchEventWith(Event.RTMP_STATUS, false, code.data(""))
        }
        synchronized(this) {
            if (socket === transport) {
                readyState = ReadyState.Closed
                isConnected = false
            }
        }
        return true
    }

    @Synchronized
    private fun onConnect(transport: NetSocket) {
        if (socket !== transport || readyState != ReadyState.Uninitialized) return
        chunkSizeC = RtmpChunk.DEFAULT_SIZE
        chunkSizeS = RtmpChunk.DEFAULT_SIZE
        handshake = RtmpHandshake()
        readyState = ReadyState.VersionSent
        val c0C1Packet = handshake.c0C1Packet
        handshakeStartedAtNanos = System.nanoTime()
        transport.doOutput(c0C1Packet)
    }

    private fun onInput(
        transport: NetSocket,
        buffer: ByteBuffer,
    ) {
        synchronized(this) {
            if (socket !== transport) return
            if (readyState == ReadyState.VersionSent) {
                if (buffer.remaining() < RtmpHandshake.SIGNAL_SIZE + 1) return
                handshake.s0S1Packet = buffer
                transport.doOutput(handshake.c2Packet)
                buffer.position(RtmpHandshake.SIGNAL_SIZE + 1)
                readyState = ReadyState.AckSent
            }

            if (readyState == ReadyState.AckSent) {
                if (buffer.remaining() < RtmpHandshake.SIGNAL_SIZE) return
                handshake.s2Packet = buffer
                buffer.position(buffer.position() + RtmpHandshake.SIGNAL_SIZE)
                handshakeDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - handshakeStartedAtNanos)
                readyState = ReadyState.HandshakeDone
                isConnected = true
                connection.uri?.let {
                    connection.doOutput(RtmpChunk.ZERO, connection.createConnectionMessage(it))
                }
            }
        }

        if (socket !== transport || readyState != ReadyState.HandshakeDone || !buffer.hasRemaining()) return
        try {
            connection.listen(buffer)
        } catch (e: IndexOutOfBoundsException) {
            if (VERBOSE) Log.d(TAG, "", e)
        } catch (e: BufferUnderflowException) {
            if (VERBOSE) Log.d(TAG, "", e)
        } catch (e: IllegalArgumentException) {
            if (VERBOSE) Log.w(TAG, "", e)
            throw e
        }
    }

    companion object {
        private const val VERBOSE = false
        private var TAG = RtmpSocket::class.java.simpleName
    }
}
