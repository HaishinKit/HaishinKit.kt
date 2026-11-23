package com.haishinkit.rtmp.message

import com.haishinkit.rtmp.RtmpConnection
import java.nio.ByteBuffer

/**
 * 5.4.3 Acknowledgement (3)
 */
internal class RtmpAcknowledgementMessage : RtmpMessage(TYPE_ACK) {
    var sequence = ByteArray(CAPACITY)
        private set
    override var length: Int = CAPACITY

    override fun encode(buffer: ByteBuffer): RtmpMessage {
        buffer.put(sequence)
        return this
    }

    override fun decode(buffer: ByteBuffer): RtmpMessage {
        buffer.get(sequence)
        return this
    }

    override fun execute(connection: RtmpConnection): RtmpMessage {
        payload.clear()
        return this
    }

    companion object {
        private const val CAPACITY = 4
    }
}
