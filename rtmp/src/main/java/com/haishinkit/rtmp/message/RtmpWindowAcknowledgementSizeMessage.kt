package com.haishinkit.rtmp.message

import com.haishinkit.rtmp.RtmpChunk
import com.haishinkit.rtmp.RtmpConnection
import java.nio.ByteBuffer

/**
 * 5.4.4. Window Acknowledgement Size (5)
 */
internal class RtmpWindowAcknowledgementSizeMessage : RtmpMessage(TYPE_ACK) {
    var size: Int = 0
        private set
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
        val ack = connection.messageFactory.createRtmpWindowAcknowledgementSizeMessage()
        ack.size = size
        ack.chunkStreamID = RtmpChunk.CONTROL
        connection.doOutput(RtmpChunk.ZERO, ack)
        return this
    }

    companion object {
        private const val CAPACITY = 4
    }
}
