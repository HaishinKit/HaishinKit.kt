package com.haishinkit.rtmp

import android.util.Log
import com.haishinkit.net.NetSocket
import com.haishinkit.net.NetSocketImpl
import com.haishinkit.rtmp.event.Event
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

internal class RtmpSocket(
    val connection: RtmpConnection,
) : NetSocket.Listener {
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
    private var socket: NetSocket? = null
    private val handshake: RtmpHandshake by lazy {
        RtmpHandshake()
    }
    private var readyState = ReadyState.Uninitialized
        set(value) {
            field = value
            connection.onSocketReadyStateChange(this, value)
        }

    fun connect(
        dstName: String,
        dstPort: Int,
        isSecure: Boolean,
    ) {
        socket?.listener = null
        socket = NetSocketImpl()
        socket?.listener = this
        socket?.connect(dstName, dstPort, isSecure)
    }

    fun doOutput(buffer: ByteBuffer) {
        socket?.doOutput(buffer)
    }

    fun createByteBuffer(capacity: Int): ByteBuffer = socket?.createByteBuffer(capacity) ?: ByteBuffer.allocate(capacity)

    fun close(disconnected: Boolean) {
        if (!isConnected) return
        var data: Any? = null
        if (disconnected) {
            data =
                if (readyState == ReadyState.HandshakeDone) {
                    RtmpConnection.Code.CONNECT_CLOSED.data("")
                } else {
                    RtmpConnection.Code.CONNECT_FAILED.data("")
                }
        }
        readyState = ReadyState.Closing
        socket?.close(disconnected)
        data?.let {
            connection.dispatchEventWith(Event.RTMP_STATUS, false, it)
        }
        readyState = ReadyState.Closed
        isConnected = false
    }

    override fun onSocketError() {
        close(false)
        connection.dispatchEventWith(Event.IO_ERROR, false)
        Log.i(TAG, "a connection was failed")
    }

    override fun onConnect() {
        chunkSizeC = RtmpChunk.DEFAULT_SIZE
        chunkSizeS = RtmpChunk.DEFAULT_SIZE
        handshake.clear()
        readyState = ReadyState.VersionSent
        socket?.doOutput(handshake.c0C1Packet)
    }

    override fun onClose(disconnected: Boolean) {
        close(disconnected)
    }

    override fun onInput(buffer: ByteBuffer) {
        when (readyState) {
            ReadyState.VersionSent -> {
                if (buffer.limit() < RtmpHandshake.SIGNAL_SIZE + 1) {
                    return
                }
                handshake.s0S1Packet = buffer
                socket?.doOutput(handshake.c2Packet)
                buffer.position(RtmpHandshake.SIGNAL_SIZE + 1)
                readyState = ReadyState.AckSent
                if (buffer.limit() - buffer.position() == RtmpHandshake.SIGNAL_SIZE) {
                    onInput(buffer.slice())
                    buffer.position(3073)
                }
            }

            ReadyState.AckSent -> {
                if (buffer.limit() < RtmpHandshake.SIGNAL_SIZE) {
                    return
                }
                handshake.s2Packet = buffer
                buffer.position(RtmpHandshake.SIGNAL_SIZE)
                readyState = ReadyState.HandshakeDone
                isConnected = true
                connection.uri?.let {
                    connection.doOutput(RtmpChunk.ZERO, connection.createConnectionMessage(it))
                }
            }

            ReadyState.HandshakeDone ->
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

            else -> {}
        }
    }

    companion object {
        private const val VERBOSE = false
        private var TAG = RtmpSocket::class.java.simpleName
    }
}
