package com.haishinkit.rtmp.iso

import java.nio.ByteBuffer

internal sealed class NalUnit(
    open val type: UByte,
    open val payload: ByteBuffer,
) {
    data class Hevc(
        override val type: UByte,
        val temporalIdPlusOne: UByte,
        override val payload: ByteBuffer,
    ) : NalUnit(type, payload) {
        val length: Int
            get() = payload.remaining() + 2

        fun encode(buffer: IsoTypeBuffer) {
            buffer.putUByte(type.toUInt() shl 1)
            buffer.putUByte(temporalIdPlusOne)
            buffer.putBytes(payload)
        }

        companion object {
            fun create(array: ByteArray): Hevc {
                val first = array[0].toUInt()
                val second = array[1].toUByte()
                return Hevc(
                    ((first and 0x7eu) shr 1).toUByte(),
                    second and 0b111u,
                    ByteBuffer.wrap(array.sliceArray(2 until array.size)),
                )
            }

            fun create(buffer: ByteBuffer): Hevc {
                val first = buffer.get().toUInt()
                val second = buffer.get().toUByte()
                return Hevc(
                    ((first and 0x7eu) shr 1).toUByte(),
                    second and 0b111u,
                    buffer,
                )
            }
        }
    }

    data class Avc(
        override val type: UByte,
        override val payload: ByteBuffer,
    ) : NalUnit(type, payload) {
        companion object {
            fun create(buffer: ByteBuffer): Avc {
                val first = buffer.get().toUInt()
                return Avc(
                    (first and 0x1fu).toUByte(),
                    buffer,
                )
            }
        }
    }
}
