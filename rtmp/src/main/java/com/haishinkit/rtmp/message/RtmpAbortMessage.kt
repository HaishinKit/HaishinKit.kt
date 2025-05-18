package com.haishinkit.rtmp.message

import com.haishinkit.rtmp.RtmpConnection
import java.nio.ByteBuffer

/**
 * 5.4.2. Abort Message (2)
 */
internal class RtmpAbortMessage : RtmpMessage(TYPE_ABORT) {
    var discarded: Int = 0
        private set
    override var length: Int = CAPACITY

    override fun encode(buffer: ByteBuffer): RtmpMessage {
        buffer.putInt(discarded)
        return this
    }

    override fun decode(buffer: ByteBuffer): RtmpMessage {
        discarded = buffer.int
        return this
    }

    override fun execute(connection: RtmpConnection): RtmpMessage = this

    companion object {
        private const val CAPACITY = 4
    }
}
