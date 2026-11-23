package com.haishinkit.rtmp.message

import com.haishinkit.rtmp.RtmpConnection
import java.nio.ByteBuffer

/**
 * 5.4.1. Set Chunk Size (1)
 */
internal class RtmpSetChunkSizeMessage : RtmpMessage(TYPE_CHUNK_SIZE) {
    var size: Int = 0
    override var length: Int = CAPACITY

    override fun encode(buffer: ByteBuffer): RtmpMessage {
        buffer.putInt(size)
        return this
    }

    override fun decode(buffer: ByteBuffer): RtmpMessage {
        size = buffer.int
        return this
    }

    override fun execute(connection: RtmpConnection): RtmpMessage {
        connection.socket.chunkSizeC = size
        return this
    }

    companion object {
        private const val CAPACITY = 4
    }
}
