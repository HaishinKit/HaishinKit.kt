package com.haishinkit.rtmp

import java.nio.ByteBuffer
import java.util.Random

internal class RtmpHandshake {
    var c0C1Packet: ByteBuffer = ByteBuffer.allocate(SIGNAL_SIZE + 1)
        get() {
            if (field.position() == 0) {
                val random = Random()
                field.put(0x03)
                field.position(1 + 8)
                for (i in 0..SIGNAL_SIZE - 9) {
                    field.put(random.nextInt().toByte())
                }
            }
            return field
        }

    var c2Packet: ByteBuffer = ByteBuffer.allocate(SIGNAL_SIZE)

    var s0S1Packet: ByteBuffer = ByteBuffer.allocate(SIGNAL_SIZE + 1)
        set(value) {
            field = ByteBuffer.wrap(value.array(), 0, SIGNAL_SIZE + 1)
            c2Packet.clear()
            c2Packet.put(value.array(), 1, 4)
            c2Packet.position(8)
            c2Packet.put(value.array(), 9, SIGNAL_SIZE - 8)
        }

    var s2Packet: ByteBuffer = ByteBuffer.allocate(SIGNAL_SIZE)
        set(value) {
            field = ByteBuffer.wrap(value.array(), 0, SIGNAL_SIZE)
        }

    fun clear() {
        c0C1Packet.clear()
        s0S1Packet.clear()
        c2Packet.clear()
        s2Packet.clear()
    }

    companion object {
        const val SIGNAL_SIZE = 1536
    }
}
