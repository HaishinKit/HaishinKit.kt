package com.haishinkit.rtmp.message

import androidx.core.util.Pools
import com.haishinkit.rtmp.RtmpChunk
import com.haishinkit.rtmp.RtmpConnection
import java.nio.ByteBuffer

internal open class RtmpMessage(
    val type: Byte,
    private val pool: Pools.Pool<RtmpMessage>? = null,
) {
    var chunk: RtmpChunk = RtmpChunk.ZERO
    var chunkStreamID: Short = 0
    var streamID: Int = 0
    var timestamp: Int = 0
    open var payload: ByteBuffer = EMPTY_BYTE_BUFFER
        get() {
            if (field.capacity() < length) {
                field = ByteBuffer.allocate(length)
            } else {
                field.limit(length)
            }
            return field
        }
    open var length: Int = -1

    open fun encode(buffer: ByteBuffer): RtmpMessage {
        TODO("$TAG#encode")
    }

    open fun decode(buffer: ByteBuffer): RtmpMessage {
        TODO("$TAG#decode")
    }

    open fun execute(connection: RtmpConnection): RtmpMessage {
        TODO("$TAG#execute")
    }

    open fun release(): Boolean = pool?.release(this) ?: false

    companion object {
        const val TYPE_CHUNK_SIZE: Byte = 0x01
        const val TYPE_ABORT: Byte = 0x02
        const val TYPE_ACK: Byte = 0x03
        const val TYPE_USER: Byte = 0x04
        const val TYPE_WINDOW_ACK: Byte = 0x05
        const val TYPE_BANDWIDTH: Byte = 0x06
        const val TYPE_AUDIO: Byte = 0x08
        const val TYPE_VIDEO: Byte = 0x09
        const val TYPE_AMF3_DATA: Byte = 0x0F
        const val TYPE_AMF3_SHARED: Byte = 0x10
        const val TYPE_AMF3_COMMAND: Byte = 0x11
        const val TYPE_AMF0_DATA: Byte = 0x12
        const val TYPE_AMF0_SHARED: Byte = 0x13
        const val TYPE_AMF0_COMMAND: Byte = 0x14
        const val TYPE_AGGREGATE: Byte = 0x16

        val EMPTY_BYTE_BUFFER: ByteBuffer = ByteBuffer.allocate(0)
        private val TAG = RtmpMessage::class.java.simpleName
    }
}
