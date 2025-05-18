package com.haishinkit.rtmp.message

import com.haishinkit.rtmp.RtmpConnection
import java.nio.ByteBuffer

/**
 * 5.4.5. Set Peer Bandwidth (6)
 */
internal class RtmpSetPeerBandwidthMessage : RtmpMessage(TYPE_BANDWIDTH) {
    var size = 0
        private set
    var limit = LIMIT_HARD
        private set
    override var length: Int = CAPACITY

    override fun encode(buffer: ByteBuffer): RtmpMessage {
        buffer.putInt(size)
        buffer.put(limit)
        return this
    }

    override fun decode(buffer: ByteBuffer): RtmpMessage {
        size = buffer.int
        limit = buffer.get()
        return this
    }

    override fun execute(connection: RtmpConnection): RtmpMessage {
        connection.socket.bandWidth = size
        return this
    }

    companion object {
        const val LIMIT_HARD: Byte = 0x00
        const val LIMIT_SOFT: Byte = 0x01
        const val LIMIT_DYNAMIC: Byte = 0x02
        const val LIMIT_UNKNOWN: Byte = Byte.MAX_VALUE

        private const val CAPACITY = 5
    }
}
