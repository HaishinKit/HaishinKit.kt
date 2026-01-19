package com.haishinkit.rtmp

import com.haishinkit.rtmp.message.RtmpMessage
import java.nio.ByteBuffer
import kotlin.math.min

internal enum class RtmpChunk(
    val rawValue: Byte,
) {
    ZERO(0x00),
    ONE(0x01),
    TWO(0x02),
    THREE(0x03),
    ;

    fun encode(
        socket: RtmpSocket,
        message: RtmpMessage,
    ) {
        val payload = message.payload
        payload.clear()
        message.encode(payload)
        payload.flip()

        val length = payload.limit()
        val timestamp = message.timestamp
        val chunkSize = socket.chunkSizeS
        var buffer = socket.createByteBuffer(length(message.chunkStreamID) + chunkSize)
        putHeader(buffer, message.chunkStreamID)
        buffer
            .put((timestamp shr 16).toByte())
            .put((timestamp shr 8).toByte())
            .put(timestamp.toByte())

        when (this) {
            ZERO -> {
                buffer
                    .put((length shr 16).toByte())
                    .put((length shr 8).toByte())
                    .put(length.toByte())
                buffer.put(message.type)
                val streamID = message.streamID
                // message streamID is a litleEndian
                buffer
                    .put(streamID.toByte())
                    .put((streamID shr 8).toByte())
                    .put((streamID shr 16).toByte())
                    .put((streamID shr 24).toByte())
            }

            ONE -> {
                buffer
                    .put((length shr 16).toByte())
                    .put((length shr 8).toByte())
                    .put(length.toByte())
                buffer.put(message.type)
            }

            else -> {
            }
        }
        var offset = 0
        var remaining = min(chunkSize, length)
        do {
            if (0 < offset) {
                buffer = socket.createByteBuffer(length(message.chunkStreamID) + chunkSize)
                THREE.putHeader(buffer, message.chunkStreamID)
            }
            buffer.put(payload.array(), offset, remaining)
            socket.doOutput(buffer)
            offset += remaining
            remaining = min(chunkSize, length - offset)
        } while (0 < remaining)
    }

    fun decode(
        chunkStreamID: Short,
        connection: RtmpConnection,
        buffer: ByteBuffer,
    ): RtmpMessage {
        var timestamp = 0
        var length = 0
        var type: Byte = 0
        var streamID = 0

        when (this) {
            ZERO -> {
                timestamp = getInt(buffer)
                length = getInt(buffer)
                type = buffer.get()
                streamID = Integer.reverseBytes(buffer.int)
                if (timestamp == 16777215) {
                    timestamp = buffer.int
                }
            }

            ONE -> {
                timestamp = getInt(buffer)
                length = getInt(buffer)
                type = buffer.get()
                if (timestamp == 16777215) {
                    timestamp = buffer.int
                }
            }

            TWO -> {
                val message = connection.messages[chunkStreamID]!!
                message.timestamp = getInt(buffer)
                return message
            }

            else -> {
            }
        }

        val message = connection.messageFactory.create(type)
        message.chunk = this
        message.chunkStreamID = chunkStreamID
        message.streamID = streamID
        message.timestamp = timestamp
        message.length = length

        return message
    }

    fun length(streamID: Short): Int {
        var basic = 3
        if (streamID <= 63) {
            basic = 1
        } else if (streamID <= 319) {
            basic = 2
        }
        return when (this) {
            ZERO -> basic + 11
            ONE -> basic + 7
            TWO -> basic + 3
            THREE -> basic + 0
        }
    }

    fun getStreamID(buffer: ByteBuffer): Short {
        buffer.position(buffer.position() - 1)
        val first = buffer.get().toInt()
        return when (first and 63) {
            0 -> {
                val bytes = ByteArray(2)
                buffer.get(bytes)
                (bytes[1] + 64).toShort()
            }

            1 -> {
                val bytes = ByteArray(3)
                buffer.get(bytes)
                (bytes[1].toInt() shl 8 or bytes[2].toInt() or 64).toShort()
            }

            else -> {
                (first and 63).toShort()
            }
        }
    }

    private fun putHeader(
        buffer: ByteBuffer,
        streamID: Short,
    ) {
        if (streamID <= 63) {
            buffer.put((rawValue.toInt() shl 6 or streamID.toInt()).toByte())
            return
        }
        if (streamID <= 319) {
            buffer.put((rawValue.toInt() shl 6 or 0).toByte()).put((streamID - 64).toByte())
            return
        }
        buffer
            .put((rawValue.toInt() shl 6 or 1).toByte())
            .put((streamID - 64 shr 8).toByte())
            .put((streamID - 64).toByte())
    }

    private fun getInt(buffer: ByteBuffer): Int {
        val first = buffer.get().toInt()
        val second = buffer.get().toInt()
        val third = buffer.get().toInt()
        return (first and 0xFF) shl 16 or ((second and 0xFF) shl 8) or (third and 0xFF)
    }

    companion object {
        const val CONTROL: Short = 0x02
        const val COMMAND: Short = 0x03
        const val AUDIO: Short = 0x04
        const val VIDEO: Short = 0x05
        const val DEFAULT_SIZE = 128

        fun chunk(value: Byte): RtmpChunk =
            when ((value.toInt() and 0xff) shr 6) {
                0 -> ZERO
                1 -> ONE
                2 -> TWO
                3 -> THREE
                else -> throw IllegalArgumentException("value=$value")
            }

        @Suppress("unused")
        private val TAG = RtmpChunk::class.java.simpleName
    }
}
