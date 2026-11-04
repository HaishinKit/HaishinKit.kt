package com.haishinkit.net

import android.util.Log
import androidx.core.util.Pools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.CoroutineContext

/**
 * A coroutine-based TCP/TLS transport with pooled output buffers.
 */
class NetSocketImpl :
    NetSocket,
    CoroutineScope {
    override var timeout = DEFAULT_TIMEOUT
    override var listener: NetSocket.Listener? = null
    override val totalBytesIn = AtomicLong(0)
    override val totalBytesOut = AtomicLong(0)
    override val queueBytesOut = AtomicLong(0)
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO
    private val connectionGuard = Any()
    private var connectionJob: Job? = null
    private var outputJob: Job? = null
    private var inputBuffer = ByteBuffer.allocate(DEFAULT_WINDOW_SIZE_C)
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
        set(value) {
            if (value == null) {
                field?.close()
            }
            field = value
        }
    private var outputStream: OutputStream? = null
        set(value) {
            if (value == null) {
                field?.close()
            }
            field = value
        }
    private var outputQueue = LinkedBlockingDeque<ByteBuffer>()
    private var outputBufferPool = Pools.SimplePool<ByteBuffer>(1024)

    @Volatile
    private var keepAlive = false

    override fun connect(
        dstName: String,
        dstPort: Int,
        isSecure: Boolean,
    ) {
        synchronized(connectionGuard) {
            if (keepAlive) return
            keepAlive = true
            totalBytesIn.set(0)
            totalBytesOut.set(0)
            queueBytesOut.set(0)
            connectionJob =
                launch(coroutineContext, start = CoroutineStart.LAZY) {
                    doConnection(dstName, dstPort, isSecure)
                }.also { it.start() }
        }
    }

    override fun close(disconnected: Boolean) {
        val notify =
            synchronized(connectionGuard) {
                val active = keepAlive
                keepAlive = false
                runCatching { socket?.close() }
                runCatching { inputStream = null }
                runCatching { outputStream = null }
                outputQueue.add(ByteBuffer.allocate(0))
                active
            }
        if (notify) listener?.onClose(disconnected)
    }

    /** Waits until connection setup and both I/O loops have exited after [close]. */
    suspend fun awaitClosed() {
        connectionJob?.join()
        outputJob?.join()
    }

    override fun doOutput(buffer: ByteBuffer) {
        try {
            buffer.flip()
            queueBytesOut.addAndGet(buffer.remaining().toLong())
            outputQueue.put(buffer)
        } catch (e: InterruptedException) {
            Log.v(TAG, "", e)
        }
    }

    override fun createByteBuffer(capacity: Int): ByteBuffer {
        synchronized(outputBufferPool) {
            var byteBuffer = outputBufferPool.acquire() ?: ByteBuffer.allocate(capacity)
            if (byteBuffer.capacity() != capacity) {
                byteBuffer = ByteBuffer.allocate(capacity)
            } else {
                byteBuffer.clear()
            }
            return byteBuffer
        }
    }

    private fun doInput() {
        try {
            val inputStream = inputStream ?: return
            val offset = inputBuffer.position()
            val result = inputStream.read(inputBuffer.array(), offset, inputBuffer.remaining())
            if (-1 < result) {
                inputBuffer.position(offset + result)
                inputBuffer.flip()
                totalBytesIn.addAndGet(result.toLong())
                listener?.onInput(inputBuffer)
                if (inputBuffer.hasRemaining()) {
                    val remaining = inputBuffer.slice()
                    inputBuffer.clear()
                    inputBuffer.put(remaining)
                } else {
                    inputBuffer.clear()
                }
            } else {
                close(true)
            }
        } catch (e: SocketException) {
            Log.w(TAG, "", e)
            close(true)
        } catch (e: IOException) {
            Log.w(TAG, "", e)
            close(true)
        }
    }

    private fun doOutput(socket: Socket) {
        while (keepAlive) {
            val buffer = outputQueue.take()
            if (socket.isClosed) {
                break
            }
            try {
                val outputStream = outputStream ?: break
                val remaining = buffer.remaining().toLong()
                outputStream.write(buffer.array(), 0, buffer.remaining())
                outputStream.flush()
                outputQueue.remove(buffer)
                totalBytesOut.addAndGet(remaining)
                queueBytesOut.addAndGet(remaining * -1)
                synchronized(outputBufferPool) {
                    outputBufferPool.release(buffer)
                }
            } catch (e: SocketException) {
                Log.w(TAG, "", e)
                close(false)
            } catch (e: IOException) {
                Log.w(TAG, "", e)
                close(false)
            }
        }
    }

    private fun doConnection(
        dstName: String,
        dstPort: Int,
        isSecure: Boolean,
    ) {
        if (!keepAlive) return
        outputQueue.clear()
        try {
            this.socket = createSocket(dstName, dstPort, isSecure)
        } catch (e: Exception) {
            Log.w(TAG, "", e)
            if (keepAlive) {
                close(true)
                listener?.onSocketError()
            }
        }
        try {
            val socket = socket ?: return
            if (socket.isConnected && keepAlive) {
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
                outputJob =
                    launch(Dispatchers.IO) {
                        doOutput(socket)
                    }
                listener?.onConnect()
                while (keepAlive) {
                    doInput()
                    try {
                        Thread.sleep(KEEP_ALIVE_SLEEP_INTERVAL)
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "", e)
            close(true)
        }
    }

    private fun createSocket(
        dstName: String,
        dstPort: Int,
        isSecure: Boolean,
    ): Socket {
        val candidate = Socket()
        synchronized(connectionGuard) {
            if (!keepAlive) {
                candidate.close()
                return candidate
            }
            socket = candidate
        }
        // Publish the socket before blocking TCP/TLS setup so close can interrupt it.
        candidate.connect(InetSocketAddress(dstName, dstPort))
        if (!isSecure) return candidate
        val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(candidate, dstName, dstPort, true) as SSLSocket
        synchronized(connectionGuard) {
            if (!keepAlive) {
                secure.close()
                return secure
            }
            socket = secure
        }
        secure.startHandshake()
        return secure
    }

    companion object {
        const val DEFAULT_TIMEOUT: Int = 1000

        private const val DEFAULT_WINDOW_SIZE_C = Short.MAX_VALUE.toInt() * 5
        private const val KEEP_ALIVE_SLEEP_INTERVAL = 10L
        private val TAG = NetSocketImpl::class.java.simpleName
    }
}
