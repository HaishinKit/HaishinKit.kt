package com.haishinkit.rtc

import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Small HTTP/1.1 fixture; no JDK-only HTTP server or external test dependency. */
internal class TestHTTPServer {
    val address: InetSocketAddress
        get() = socket.localSocketAddress as InetSocketAddress
    private val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    private val handlers = ConcurrentHashMap<String, (Request) -> Unit>()
    private val failure = AtomicReference<Throwable?>()
    private var worker: Thread? = null

    fun createContext(
        path: String,
        handler: (Request) -> Unit,
    ) {
        handlers[path] = handler
    }

    fun start() {
        worker =
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    try {
                        val connection = socket.accept()
                        thread(isDaemon = true) {
                            try {
                                val request = Request(connection)
                                (handlers[request.path] ?: error("No handler for ${request.path}"))(request)
                            } catch (cause: Throwable) {
                                failure.compareAndSet(null, cause)
                                connection.close()
                            }
                        }
                    } catch (cause: java.net.SocketException) {
                        if (!socket.isClosed) failure.compareAndSet(null, cause)
                    }
                }
            }
    }

    fun stop() {
        socket.close()
        worker?.join(1000)
        failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
    }

    class Headers {
        private val values = mutableMapOf<String, String>()

        fun add(
            key: String,
            value: String,
        ) {
            values[key.lowercase()] = value
        }

        fun getFirst(key: String): String? = values[key.lowercase()]

        fun lines(): String = values.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
    }

    class Request(private val socket: Socket) {
        val requestMethod: String
        val path: String
        val requestHeaders = Headers()
        val responseHeaders = Headers()
        val requestBody: ByteArrayInputStream
        val responseBody = socket.getOutputStream()

        init {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().buffered()

            fun line(): String {
                val result = StringBuilder()
                while (true) {
                    val byte = input.read()
                    check(byte >= 0 && result.length < 65536)
                    if (byte == 10) return result.toString().trimEnd('\r')
                    result.append(byte.toChar())
                }
            }
            val first = line().split(' ')
            requestMethod = first[0]
            path = first[1]
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                val index = header.indexOf(':')
                requestHeaders.add(header.substring(0, index), header.substring(index + 1).trim())
            }
            val length = requestHeaders.getFirst("Content-Length")?.toInt() ?: 0
            require(length in 0..1024 * 1024)
            val data = ByteArray(length)
            var position = 0
            while (position < length) {
                val count = input.read(data, position, length - position)
                check(count > 0)
                position += count
            }
            requestBody = ByteArrayInputStream(data)
        }

        fun sendResponseHeaders(
            code: Int,
            length: Long,
        ) {
            responseHeaders.add("Content-Length", maxOf(0, length).toString())
            responseHeaders.add("Connection", "close")
            responseBody.write("HTTP/1.1 $code Test\r\n${responseHeaders.lines()}\r\n".toByteArray())
            responseBody.flush()
        }

        fun close() = socket.close()
    }
}
