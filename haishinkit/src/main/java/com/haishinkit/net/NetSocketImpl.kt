package com.haishinkit.net

import android.util.Log
import androidx.core.util.Pools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.CoroutineContext

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
        if (socket?.isConnected == true) {
            return
        }
        keepAlive = true
        totalBytesIn.set(0)
        totalBytesOut.set(0)
        queueBytesOut.set(0)
        launch(coroutineContext) {
            doConnection(dstName, dstPort, isSecure)
        }
    }

    override fun close(disconnected: Boolean) {
        if (socket?.isClosed == true) {
            return
        }
        keepAlive = false
        inputStream = null
        outputStream = null
        socket?.close()
        outputQueue.add(ByteBuffer.allocate(0))
        listener?.onClose(disconnected)
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
        outputQueue.clear()
        try {
            this.socket = createSocket(dstName, dstPort, isSecure)
        } catch (e: Exception) {
            Log.w(TAG, "", e)
            close(true)
            listener?.onSocketError()
        }
        try {
            val socket = socket ?: return
            if (socket.isConnected) {
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
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
        if (isSecure) {
            val socket = SSLSocketFactory.getDefault().createSocket(dstName, dstPort) as SSLSocket
            socket.startHandshake()
            return socket
        }
        return Socket(dstName, dstPort)
    }

    companion object {
        const val DEFAULT_TIMEOUT: Int = 1000

        private const val DEFAULT_WINDOW_SIZE_C = Short.MAX_VALUE.toInt() * 5
        private const val KEEP_ALIVE_SLEEP_INTERVAL = 10L
        private val TAG = NetSocketImpl::class.java.simpleName
    }
}
