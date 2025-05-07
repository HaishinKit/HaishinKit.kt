package com.haishinkit.rtmp

import android.util.Log
import com.haishinkit.event.Event
import com.haishinkit.event.EventDispatcher
import com.haishinkit.event.EventUtils
import com.haishinkit.event.IEventListener
import com.haishinkit.net.Responder
import com.haishinkit.rtmp.message.RtmpCommandMessage
import com.haishinkit.rtmp.message.RtmpMessage
import com.haishinkit.rtmp.message.RtmpMessageFactory
import com.haishinkit.util.UriUtil
import java.net.URI
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.schedule

/**
 * An object that creates a two-way RTMP connection.
 */
@Suppress("UNUSED", "MemberVisibilityCanBePrivate")
class RtmpConnection : EventDispatcher(null) {
    /**
     * NetStatusEvent#info.code for [RtmpConnection]
     */
    @Suppress("UNUSED")
    enum class Code(
        val rawValue: String,
        val level: String,
    ) {
        CALL_BAD_VERSION("NetConnection.Call.BadVersion", "error"),
        CALL_FAILED("NetConnection.Call.Failed", "error"),
        CALL_PROHIBITED("NetConnection.Call.Prohibited", "error"),
        CONNECT_APP_SHUTDOWN("NetConnection.Connect.AppShutdown", "status"),
        CONNECT_CLOSED("NetConnection.Connect.Closed", "status"),
        CONNECT_FAILED("NetConnection.Connect.Failed", "error"),
        CONNECT_IDLE_TIME_OUT("NetConnection.Connect.IdleTimeOut", "status"),
        CONNECT_INVALID_APP("NetConnection.Connect.InvalidApp", "error"),
        CONNECT_NETWORK_CHANGE("NetConnection.Connect.NetworkChange", "status"),
        CONNECT_REJECTED("NetConnection.Connect.Rejected", "status"),
        CONNECT_SUCCESS("NetConnection.Connect.Success", "status"),
        ;

        fun data(description: String): Map<String, Any> {
            val data = HashMap<String, Any>()
            data["code"] = rawValue
            data["level"] = level
            if (description.isNotEmpty()) {
                data["description"] = description
            }
            return data
        }
    }

    private inner class EventListener(
        private val connection: RtmpConnection,
    ) : IEventListener {
        override fun handleEvent(event: Event) {
            val data = EventUtils.toMap(event)
            if (VERBOSE) {
                Log.i(TAG, data.toString())
            }
            when (data["code"].toString()) {
                Code.CONNECT_SUCCESS.rawValue -> {
                    timerTask =
                        Timer().schedule(0, 1000) {
                            for (stream in streams) stream.value.on()
                        }
                    val message = messageFactory.createRtmpSetChunkSizeMessage()
                    message.size = DEFAULT_CHUNK_SIZE_S
                    message.chunkStreamID = RtmpChunk.CONTROL
                    connection.socket.chunkSizeS = DEFAULT_CHUNK_SIZE_S
                    connection.doOutput(RtmpChunk.ZERO, message)
                }
            }
        }
    }

    /**
     * The URI passed to the RTMPConnection.connect() method.
     */
    var uri: URI? = null
        private set

    /**
     * Specifies the URL of .swf.
     */
    var swfUrl: String? = null

    /**
     * Specifies the URL of an HTTP referer.
     */
    var pageUrl: String? = null

    /**
     * Specifies the name of application.
     */
    var flashVer = DEFAULT_FLASH_VER_SWF

    /**
     * Specifies the outgoing RTMPChunkSize.
     */
    var chunkSize: Int
        get() = socket.chunkSizeS
        set(value) {
            socket.chunkSizeS = value
        }

    /**
     * This instance connected to server(true) or not(false).
     */
    val isConnected: Boolean
        get() = socket.isConnected

    /**
     * Specifies the time to wait for TCP/IP Handshake done.
     */
    var timeout: Int
        get() = socket.timeout
        set(value) {
            socket.timeout = value
        }

    /**
     * The statistics of total incoming bytes.
     */
    val totalBytesIn: Long
        get() = socket.totalBytesIn

    /**
     * The statistics of total outgoing bytes.
     */
    val totalBytesOut: Long
        get() = socket.totalBytesOut

    /**
     * The object encoding for this RTMPConnection instance.
     */
    internal val objectEncoding = RtmpObjectEncoding.AMF0
    internal val messages = ConcurrentHashMap<Short, RtmpMessage>()
    internal val streams = ConcurrentHashMap<Int, RtmpStream>()
    internal val streamsmap = ConcurrentHashMap<Short, Int>()
    internal val responders = ConcurrentHashMap<Int, Responder>()
    internal val socket = RtmpSocket(this)
    internal var transactionID = 0
    internal val messageFactory = RtmpMessageFactory(4)
    private var timerTask: TimerTask? = null
        set(value) {
            timerTask?.cancel()
            field = value
        }
    private var arguments: MutableList<Any?> = mutableListOf()
    private val authenticator: RtmpAuthenticator by lazy {
        RtmpAuthenticator(this)
    }

    init {
        addEventListener(Event.RTMP_STATUS, authenticator)
        addEventListener(Event.RTMP_STATUS, EventListener(this))
    }

    /**
     * Calls a command or method on RTMP Server.
     */
    fun call(
        commandName: String,
        responder: Responder?,
        vararg arguments: Any,
    ) {
        if (!isConnected) {
            return
        }
        val listArguments = mutableListOf<Any?>(arguments.size)
        for (`object` in arguments) {
            listArguments.add(`object`)
        }
        val message = RtmpCommandMessage(objectEncoding)
        message.chunkStreamID = RtmpChunk.COMMAND
        message.streamID = 0
        message.transactionID = ++transactionID
        message.commandName = commandName
        message.arguments = listArguments
        if (responder != null) {
            responders[transactionID] = responder
        }
        doOutput(RtmpChunk.ZERO, message)
    }

    /**
     * Creates a two-way connection to an application on RTMP Server.
     */
    fun connect(
        command: String,
        vararg arguments: Any?,
    ) {
        uri = URI.create(command)
        val uri = this.uri ?: return
        if (isConnected || !SUPPORTED_PROTOCOLS.containsKey(uri.scheme)) {
            return
        }
        val port = uri.port
        val isSecure = uri.scheme == "rtmps"
        this.arguments.clear()
        arguments.forEach { value -> this.arguments.add(value) }
        socket.connect(
            uri.host,
            if (port == -1) SUPPORTED_PROTOCOLS[uri.scheme] ?: DEFAULT_PORT else port,
            isSecure,
        )
    }

    /**
     * Closes the connection from the server.
     */
    fun close() {
        if (!isConnected) {
            return
        }
        timerTask = null
        for (stream in streams) {
            stream.value.close()
            streams.remove(stream.key)
        }
        socket.close(false)
    }

    /**
     * Dispose the connection for a memory management.
     */
    fun dispose() {
        timerTask = null
        for (stream in streams) {
            stream.value.dispose()
        }
        streams.clear()
    }

    internal fun doOutput(
        chunk: RtmpChunk,
        message: RtmpMessage,
    ) {
        chunk.encode(socket, message)
        message.release()
    }

    internal tailrec fun listen(buffer: ByteBuffer) {
        val rollback = buffer.position()
        try {
            val first = buffer.get()
            val chunk = RtmpChunk.chunk(first)
            val chunkSizeC = socket.chunkSizeC
            val chunkStreamID = chunk.getStreamID(buffer)
            if (chunk == RtmpChunk.THREE) {
                val message = messages[chunkStreamID]!!
                val payload = message.payload
                var remaining = payload.remaining()
                if (chunkSizeC < remaining) {
                    remaining = chunkSizeC
                }
                if (buffer.position() + remaining <= buffer.limit()) {
                    payload.put(buffer.array(), buffer.position(), remaining)
                    buffer.position(buffer.position() + remaining)
                } else {
                    buffer.position(rollback)
                    return
                }
                if (!payload.hasRemaining()) {
                    payload.flip()
                    message.decode(payload).execute(this)
                }
            } else {
                val message = chunk.decode(chunkStreamID, this, buffer)
                messages[chunkStreamID] = message
                when (chunk) {
                    RtmpChunk.ZERO -> {
                        streamsmap[chunkStreamID] = message.streamID
                    }

                    RtmpChunk.ONE -> {
                        streamsmap[chunkStreamID]?.let {
                            message.streamID = it
                        }
                    }

                    else -> {
                    }
                }
                if (message.length <= chunkSizeC) {
                    message.decode(buffer).execute(this)
                } else {
                    val payload = message.payload
                    if (buffer.position() + chunkSizeC <= buffer.limit()) {
                        payload.put(buffer.array(), buffer.position(), chunkSizeC)
                        buffer.position(buffer.position() + chunkSizeC)
                    } else {
                        buffer.position(rollback)
                        return
                    }
                }
            }
        } catch (e: BufferUnderflowException) {
            buffer.position(rollback)
            throw e
        } catch (e: IndexOutOfBoundsException) {
            buffer.position(rollback)
            throw e
        }
        if (buffer.hasRemaining()) {
            listen(buffer)
        }
    }

    internal fun createStream(stream: RtmpStream) {
        stream.fcPublishName?.let {
            // FMLE-compatible sequences
            call("releaseStream", Responder.NULL, it)
            call("FCPublish", Responder.NULL, it)
        }
        call(
            "createStream",
            object : Responder {
                override fun onResult(arguments: List<Any?>) {
                    for (s in streams) {
                        if (s.value == stream) {
                            streams.remove(s.key)
                            break
                        }
                    }
                    val id = (arguments[0] as Double).toInt()
                    stream.id = id
                    streams[id] = stream
                    stream.readyState = RtmpStream.ReadyState.OPEN
                }

                override fun onStatus(arguments: List<Any?>) {
                    Log.w("$TAG#onStatus", arguments.toString())
                }
            },
        )
    }

    internal fun onSocketReadyStateChange(
        socket: RtmpSocket,
        readyState: RtmpSocket.ReadyState,
    ) {
        if (VERBOSE) {
            Log.d(TAG, readyState.toString())
        }
        when (readyState) {
            RtmpSocket.ReadyState.Closed -> {
                transactionID = 0
                messages.clear()
                streamsmap.clear()
                responders.clear()
            }

            else -> {
            }
        }
    }

    internal fun createConnectionMessage(uri: URI): RtmpMessage {
        val paths =
            uri.path
                .split("/".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()
        val message = RtmpCommandMessage(RtmpObjectEncoding.AMF0)
        val commandObject = mutableMapOf<String, Any?>()
        var app = paths[1]
        if (uri.query != null) {
            app += "?" + uri.query
        }
        commandObject["app"] = app
        commandObject["flashVer"] = flashVer
        commandObject["swfUrl"] = swfUrl
        commandObject["tcUrl"] = UriUtil.withoutUserInfo(uri)
        commandObject["fpad"] = false
        commandObject["capabilities"] = DEFAULT_CAPABILITIES
        commandObject["audioCodecs"] = SUPPORT_SND_AAC
        commandObject["videoCodecs"] = SUPPORT_VID_H264
        commandObject["videoFunction"] = SUPPORT_VID_CLIENT_SEEK
        commandObject["pageUrl"] = pageUrl
        commandObject["objectEncoding"] = objectEncoding.rawValue
        // Extending NetConnection connect Command fourCcList
        // commandObject["fourCcList"] = SUPPORTED_FOURCC_LIST
        message.chunkStreamID = RtmpChunk.COMMAND
        message.streamID = 0
        message.commandName = "connect"
        message.transactionID = ++transactionID
        message.commandObject = commandObject
        message.arguments = arguments
        if (VERBOSE) {
            Log.d(TAG, message.toString())
        }
        return message
    }

    companion object {
        val SUPPORTED_PROTOCOLS = mapOf("rtmp" to 1935, "rtmps" to 443)
        val SUPPORTED_FOURCC_LIST = listOf("hvc1")
        const val DEFAULT_PORT = 1935
        const val DEFAULT_FLASH_VER_SWF = "LNX 9,0,124,2"
        const val DEFAULT_FLASH_VER_FMLE = "FMLE/3.0 (compatible; FMSc/1.0)"

        private const val TAG = "RtmpConnection"
        private const val DEFAULT_CHUNK_SIZE_S = 1024 * 8
        private const val DEFAULT_CAPABILITIES = 239
        private const val VERBOSE = false

        private const val SUPPORT_SND_NONE: Short = 0x001
        private const val SUPPORT_SND_ADPCM: Short = 0x002
        private const val SUPPORT_SND_MP3: Short = 0x004
        private const val SUPPORT_SND_INTEL: Short = 0x008
        private const val SUPPORT_SND_UNUSED: Short = 0x0010
        private const val SUPPORT_SND_NELLY8: Short = 0x0020
        private const val SUPPORT_SND_NELLY: Short = 0x0040
        private const val SUPPORT_SND_G711A: Short = 0x0080
        private const val SUPPORT_SND_G711U: Short = 0x0100
        private const val SUPPORT_SND_AAC: Short = 0x0200
        private const val SUPPORT_SND_SPEEX: Short = 0x0800
        private const val SUPPORT_SND_ALL: Short = 0x0FFF

        private const val SUPPORT_VID_UNUSED: Short = 0x001
        private const val SUPPORT_VID_JPEG: Short = 0x001
        private const val SUPPORT_VID_SORENSON: Short = 0x004
        private const val SUPPORT_VID_HOMEBREW: Short = 0x008
        private const val SUPPORT_VID_VP6: Short = 0x0010
        private const val SUPPORT_VID_VP6_ALPHA: Short = 0x0020
        private const val SUPPORT_VID_HOMEBREWV: Short = 0x0040
        private const val SUPPORT_VID_H264: Short = 0x0080
        private const val SUPPORT_VID_ALL: Short = 0x00FF

        private const val SUPPORT_VID_CLIENT_SEEK: Short = 0x0001
        private const val SUPPORT_VID_CLIENT_HDR: Short = 0x0002
        private const val SUPPORT_VID_CLIENT_PACKET_TYPE_METADATA: Short = 0x0004
        private const val SUPPORT_VID_CLIENT_LARGE_SCALE_TILE: Short = 0x0008
    }
}
