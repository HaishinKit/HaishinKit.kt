package com.haishinkit.rtmp

import android.content.Context
import android.graphics.Rect
import android.media.MediaFormat
import android.util.Log
import com.haishinkit.codec.Codec
import com.haishinkit.event.Event
import com.haishinkit.event.EventDispatcher
import com.haishinkit.event.EventUtils
import com.haishinkit.event.IEventDispatcher
import com.haishinkit.event.IEventListener
import com.haishinkit.iso.DecoderConfigurationRecord
import com.haishinkit.rtmp.message.RtmpCommandMessage
import com.haishinkit.rtmp.message.RtmpDataMessage
import com.haishinkit.rtmp.message.RtmpMessage
import com.haishinkit.rtmp.message.RtmpMessageFactory
import com.haishinkit.stream.Stream
import java.util.concurrent.atomic.AtomicInteger

/**
 * An object that provides the interface to control a one-way channel over a [RtmpConnection].
 */
@Suppress("UNUSED", "MemberVisibilityCanBePrivate")
class RtmpStream(
    internal var context: Context,
    internal var connection: RtmpConnection,
) : Stream(context),
    IEventDispatcher {
    data class Info(
        var resourceName: String? = null,
    )

    enum class HowToPublish(
        val rawValue: String,
    ) {
        RECORD("record"),
        APPEND("append"),
        APPEND_WITH_GAP("appendWithGap"),
        LIVE("live"),
    }

    @Suppress("UNUSED")
    enum class Code(
        val rawValue: String,
        private val level: String,
    ) {
        BUFFER_EMPTY("NetStream.Buffer.Empty", "status"),
        BUFFER_FLUSH("NetStream.Buffer.Flush", "status"),
        BUFFER_FULL("NetStream.Buffer.Full", "status"),
        CONNECT_CLOSED("NetStream.Connect.Closed", "status"),
        CONNECT_FAILED("NetStream.Connect.Failed", "error"),
        CONNECT_REJECTED("NetStream.Connect.Rejected", "error"),
        CONNECT_SUCCESS("NetStream.Connect.Success", "status"),
        DRM_UPDATE_NEEDED("NetStream.DRM.UpdateNeeded", "status"),
        FAILED("NetStream.Failed", "error"),
        MULTI_STREAM_RESET("NetStream.MulticastStream.Reset", "status"),
        PAUSE_NOTIFY("NetStream.Pause.Notify", "status"),
        PLAY_FAILED("NetStream.Play.Failed", "error"),
        PLAY_FILE_STRUCTURE_INVALID("NetStream.Play.FileStructureInvalid", "error"),
        PLAY_INSUFFICIENT_BW("NetStream.Play.InsufficientBW", "status"),
        PLAY_NO_SUPPORTED_TRACK_FOUND("NetStream.Play.NoSupportedTrackFound", "status"),
        PLAY_RESET("NetStream.Play.Reset", "status"),
        PLAY_START("NetStream.Play.Start", "status"),
        PLAY_STOP("NetStream.Play.Stop", "status"),
        PLAY_STREAM_NOT_FOUND("NetStream.Play.StreamNotFound", "error"),
        PLAY_TRANSITION("NetStream.Play.Transition", "status"),
        PLAY_UNPUBLISH_NOTIFY("NetStream.Play.UnpublishNotify", "status"),
        PUBLISH_BAD_NAME("NetStream.Publish.BadName", "status"),
        PUBLISH_IDLE("NetStream.Publish.Idle", "status"),
        PUBLISH_START("NetStream.Publish.Start", "status"),
        RECORD_ALREADY_EXISTS("NetStream.Record.AlreadyExists", "status"),
        RECORD_FAILED("NetStream.Record.Failed", "status"),
        RECORD_NO_ACCESS("NetStream.Record.NoAccess", "error"),
        RECORD_START("NetStream.Record.Start", "status"),
        RECORD_STOP("NetStream.Record.Stop", "status"),
        RECORD_DISK_QUOTA_EXCEEDED("NetStream.Record.DiskQuotaExceeded", "error"),
        SECOND_SCREEN_START("NetStream.SecondScreen.Start", "status"),
        SECOND_SCREEN_STOP("NetStream.SecondScreen.Stop", "status"),
        SEEK_FAILED("NetStream.Seek.Failed", "error"),
        SEEK_INVALID_TIME("NetStream.Seek.InvalidTime", "error"),
        SEEK_NOTIFY("NetStream.Seek.Notify", "status"),
        STEP_NOTIFY("NetStream.Step.Notify", "status"),
        UNPAUSE_NOTIFY("NetStream.Unpause.Notify", "status"),
        UNPUBLISH_SUCCESS("NetStream.Unpublish.Success", "status"),
        VIDEO_DIMENSION_CHANGE("NetStream.Video.DimensionChange", "status"),
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

    interface Listener {
        fun onStatics(
            stream: RtmpStream,
            connection: RtmpConnection,
        )
    }

    internal inner class EventListener(
        private val stream: RtmpStream,
    ) : IEventListener {
        override fun handleEvent(event: Event) {
            val data = EventUtils.toMap(event)
            when (data["code"].toString()) {
                RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                    connection.createStream(stream)
                }

                Code.PLAY_START.rawValue -> {
                    stream.readyState = ReadyState.PLAYING
                }

                Code.PLAY_UNPUBLISH_NOTIFY.rawValue -> {
                    stream.readyState = ReadyState.PLAY
                }

                Code.PUBLISH_START.rawValue -> {
                    stream.readyState = ReadyState.PUBLISHING
                }

                else -> {
                }
            }
        }
    }

    internal enum class ReadyState(
        val rawValue: Byte,
    ) {
        INITIALIZED(0x00),
        OPEN(0x01),
        PLAY(0x02),
        PLAYING(0x03),
        PUBLISH(0x04),
        PUBLISHING(0x05),
        CLOSED(0x06),
    }

    var info: Info = Info()
        private set

    var listener: Listener? = null

    /**
     * Incoming video plays on the stream or not.
     */
    var receiveVideo = true
        set(value) {
            field = value
            if (readyState != ReadyState.PLAYING) return
            val message = RtmpCommandMessage(RtmpObjectEncoding.AMF0)
            message.streamID = id
            message.chunkStreamID = RtmpChunk.COMMAND
            message.commandName = "receiveVideo"
            message.arguments = listOf(field)
            connection.doOutput(RtmpChunk.ZERO, message)
        }

    /**
     * Incoming audio plays on the stream or not.
     */
    var receiveAudio = true
        set(value) {
            field = value
            if (readyState != ReadyState.PLAYING) return
            val message = RtmpCommandMessage(RtmpObjectEncoding.AMF0)
            message.streamID = id
            message.chunkStreamID = RtmpChunk.COMMAND
            message.commandName = "receiveAudio"
            message.arguments = listOf(field)
            connection.doOutput(RtmpChunk.ZERO, message)
        }

    /**
     * Specifies the stream name used for FMLE-compatible sequences.
     */
    var fcPublishName: String? = null

    @Volatile
    var currentFPS: Int = 0
        private set

    internal var id = 0
    internal var readyState = ReadyState.INITIALIZED
        set(value) {
            Log.d(TAG, "current=$field, change=$value")
            when (field) {
                ReadyState.PLAYING -> {
                    videoTimestamp = DEFAULT_TIMESTAMP
                    audioTimestamp = DEFAULT_TIMESTAMP
                    muxer.clear()
                    view?.screen = null
                }

                ReadyState.PUBLISHING -> {
                    muxer.clear()
                }

                else -> {
                }
            }
            field = value
            when (value) {
                ReadyState.OPEN -> {
                    currentFPS = 0
                    frameCount.set(0)
                    for (message in messages) {
                        message.streamID = id
                        if (message is RtmpCommandMessage) {
                            message.transactionID = ++connection.transactionID
                            when (message.commandName) {
                                "play" -> readyState = ReadyState.PLAY
                                "publish" -> readyState = ReadyState.PUBLISH
                            }
                        }
                        connection.doOutput(RtmpChunk.ZERO, message)
                    }
                    messages.clear()
                }

                ReadyState.PLAY -> {
                    view?.screen = screen
                    mode = Codec.MODE_DECODE
                    startRunning()
                }

                ReadyState.PUBLISHING -> {
                    mode = Codec.MODE_ENCODE
                    startRunning()
                    send("@setDataFrame", "onMetaData", toMetaData())
                }

                ReadyState.CLOSED -> {
                    stopRunning()
                }

                else -> {
                }
            }
        }
    internal var muxer = RtmpMuxer(this)
    internal val messages = ArrayList<RtmpMessage>()
    internal var frameCount = AtomicInteger(0)
    internal var messageFactory = RtmpMessageFactory(4)
    internal var videoTimestamp = DEFAULT_TIMESTAMP
    internal var audioTimestamp = DEFAULT_TIMESTAMP
    private val dispatcher: EventDispatcher by lazy {
        EventDispatcher(this)
    }
    private val eventListener = EventListener(this)
    private var howToPublish = HowToPublish.LIVE

    init {
        val count = (connection.streams.count() * -1) - 1
        connection.streams[count] = this
        connection.addEventListener(Event.RTMP_STATUS, eventListener)
        if (connection.isConnected) {
            connection.createStream(this)
        }
        addEventListener(Event.RTMP_STATUS, eventListener)
        audioCodec.listener = muxer
        videoCodec.listener = muxer
    }

    /**
     * Sends streaming audio, video and data messages from a client to server.
     */
    fun publish(
        name: String?,
        howToPublish: HowToPublish = HowToPublish.LIVE,
    ) {
        val message = RtmpCommandMessage(connection.objectEncoding)
        message.transactionID = 0
        message.commandName = if (name != null) "publish" else "closeStream"
        message.chunkStreamID = RtmpChunk.AUDIO
        message.streamID = id

        if (name == null) {
            when (readyState) {
                ReadyState.PUBLISHING -> connection.doOutput(RtmpChunk.ZERO, message)
                else -> {}
            }
            return
        }

        this.howToPublish = howToPublish
        info.resourceName = name

        val arguments = mutableListOf<Any?>()
        arguments.add(name)
        arguments.add(howToPublish.rawValue)
        message.arguments = arguments

        when (readyState) {
            ReadyState.INITIALIZED, ReadyState.CLOSED -> {
                messages.add(message)
            }

            ReadyState.OPEN -> {
                connection.doOutput(RtmpChunk.ZERO, message)
                readyState = ReadyState.PUBLISH
            }

            else -> {}
        }
    }

    /**
     * Plays a media file or a live stream from server.
     */
    fun play(vararg arguments: Any) {
        val streamName = if (arguments.isEmpty()) null else arguments[0]
        val message = RtmpCommandMessage(connection.objectEncoding)
        message.transactionID = 0
        message.commandName = if (streamName != null) "play" else "closeStream"
        message.arguments = listOf(*arguments)
        message.chunkStreamID = RtmpChunk.CONTROL
        message.streamID = id

        if (streamName == null) {
            when (readyState) {
                ReadyState.PLAYING -> {
                    connection.doOutput(RtmpChunk.ZERO, message)
                }

                else -> {}
            }
            return
        }

        when (readyState) {
            ReadyState.INITIALIZED, ReadyState.CLOSED -> {
                messages.add(message)
            }

            ReadyState.OPEN, ReadyState.PLAYING -> {
                connection.doOutput(RtmpChunk.ZERO, message)
            }

            else -> {
            }
        }
    }

    /**
     * Sends a message on a published stream.
     */
    fun send(
        handlerName: String,
        vararg arguments: Any,
    ) {
        if (readyState == ReadyState.INITIALIZED || readyState == ReadyState.CLOSED) {
            return
        }
        val message = RtmpDataMessage(connection.objectEncoding)
        message.handlerName = handlerName
        arguments.forEach { value ->
            message.arguments.add(value)
        }
        message.streamID = id
        message.chunkStreamID = RtmpChunk.COMMAND
        connection.doOutput(RtmpChunk.ZERO, message)
    }

    override fun close() {
        if (readyState == ReadyState.CLOSED) {
            return
        }
        val message = RtmpCommandMessage(RtmpObjectEncoding.AMF0)
        message.streamID = 0
        message.chunkStreamID = RtmpChunk.COMMAND
        message.commandName = "deleteStream"
        message.arguments = listOf<Any>(id)
        connection.doOutput(RtmpChunk.ZERO, message)
        readyState = ReadyState.CLOSED
    }

    override fun dispose() {
        connection.removeEventListener(Event.RTMP_STATUS, eventListener)
        stopRunning()
        super.dispose()
    }

    override fun addEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    ) {
        dispatcher.addEventListener(type, listener, useCapture)
    }

    override fun dispatchEvent(event: Event) {
        dispatcher.dispatchEvent(event)
    }

    override fun dispatchEventWith(
        type: String,
        bubbles: Boolean,
        data: Any?,
    ) {
        dispatcher.dispatchEventWith(type, bubbles, data)
    }

    override fun removeEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    ) {
        dispatcher.removeEventListener(type, listener, useCapture)
    }

    override fun startRunning() {
        if (isRunning.get()) return
        muxer.clear()
        audioCodec.listener = muxer
        videoCodec.listener = muxer
        super.startRunning()
    }

    internal fun configure(inputMimeType: String) {
        audioCodec.inputMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
        audioCodec.startRunning()
        hasAudio = true
    }

    internal fun configure(decoderConfigurationRecord: DecoderConfigurationRecord): Boolean {
        if (decoderConfigurationRecord.configure(videoCodec)) {
            decoderConfigurationRecord.videoSize?.let {
                screen?.frame = Rect(0, 0, it.width, it.height)
                video.videoSize = it
            }
            videoCodec.startRunning()
            hasVideo = true
            return true
        }
        return false
    }

    internal fun doOutput(
        chunk: RtmpChunk,
        message: RtmpMessage,
    ) {
        chunk.encode(connection.socket, message)
    }

    internal fun on() {
        currentFPS = frameCount.get()
        frameCount.set(0)
        listener?.onStatics(this, connection)
    }

    private fun toMetaData(): Map<String, Any> {
        val metadata = mutableMapOf<String, Any>()
        if (hasVideo) {
            metadata["width"] = videoCodec.width
            metadata["height"] = videoCodec.height
            metadata["framerate"] = videoCodec.frameRate
            when (videoCodec.profileLevel.mime) {
                MediaFormat.MIMETYPE_VIDEO_HEVC ->
                    metadata["videocodecid"] = RtmpMuxer.FLV_VIDEO_FOUR_CC_HVC1

                MediaFormat.MIMETYPE_VIDEO_AVC -> {
                    metadata["videocodecid"] = RtmpMuxer.FLV_VIDEO_CODEC_AVC.toInt()
                }

                else -> {
                    Log.w(TAG, "not set a videocodecid for ${videoCodec.profileLevel.mime}")
                }
            }
            metadata["videodatarate"] = videoCodec.bitRate / 1000
        }
        if (hasAudio) {
            metadata["audiocodecid"] = RtmpMuxer.FLV_AUDIO_CODEC_AAC.toInt()
            metadata["audiodatarate"] = audioCodec.bitRate / 1000
        }
        return metadata
    }

    companion object {
        private const val DEFAULT_TIMESTAMP = 0
        private val TAG = RtmpStream::class.java.simpleName
    }
}
