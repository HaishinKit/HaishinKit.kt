package com.haishinkit.rtmp.iso

import java.nio.ByteBuffer

internal class IsoTypeBuffer(
    val buffer: ByteBuffer,
) {
    private var position = -1
    private var bitPosition: Int = 0
    private var currentUByte: UByte = 0u

    val boolean: Boolean
        get() {
            condition()
            val result =
                when (bitPosition) {
                    8 -> (currentUByte and BIT8) == BIT8
                    7 -> (currentUByte and BIT7) == BIT7
                    6 -> (currentUByte and BIT6) == BIT6
                    5 -> (currentUByte and BIT5) == BIT5
                    4 -> (currentUByte and BIT4) == BIT4
                    3 -> (currentUByte and BIT3) == BIT3
                    2 -> (currentUByte and BIT2) == BIT2
                    1 -> (currentUByte and BIT1) == BIT1
                    else -> false
                }
            bitPosition--
            next()
            return result
        }

    fun get(bit: Int): UByte {
        condition()
        require(bit <= bitPosition)
        var result = (currentUByte.toUInt() shr (bitPosition - bit)).toUByte()
        result =
            when (bit) {
                8 -> result and MASK8
                7 -> result and MASK7
                6 -> result and MASK6
                5 -> result and MASK5
                4 -> result and MASK4
                3 -> result and MASK3
                2 -> result and MASK2
                1 -> result and MASK1
                else -> result
            }
        bitPosition -= bit
        next()
        return result
    }

    fun getUByte(): UByte = buffer.get().toUByte()

    fun getUInt(): UInt = buffer.getInt().toUInt()

    fun getUShort(): UShort = buffer.getShort().toUShort()

    fun getULong(bit: Int): ULong {
        condition()
        require(bit % 8 == 0)
        var result: ULong = 0u
        for (i in ((bit / 8) - 1) downTo 0) {
            result += (buffer.get().toUByte().toULong() shl (i * 8))
        }
        position = buffer.position()
        bitPosition = 8
        return result
    }

    fun getBytes(length: Int): ByteArray {
        val result = ByteArray(length)
        buffer.get(result)
        return result
    }

    fun skip(bit: Int) {
        condition()
        require(0 <= bit)
        if (bitPosition < bit) {
            val remain = bit % 8
            position += ((bit - remain) / 8) - 1
            currentUByte = buffer.get(position + 1).toUByte()
            bitPosition = remain
        } else {
            bitPosition -= bit
        }
    }

    fun putUByte(value: UByte): IsoTypeBuffer {
        buffer.put(value.toByte())
        return this
    }

    fun putUByte(value: UInt): IsoTypeBuffer {
        buffer.put(value.toByte())
        return this
    }

    fun putUByte(value: Int): IsoTypeBuffer {
        buffer.put(value.toByte())
        return this
    }

    fun putUInt(value: UInt): IsoTypeBuffer {
        buffer.putInt(value.toInt())
        return this
    }

    fun putUInt48(value: ULong): IsoTypeBuffer {
        buffer.putShort((value shr 32).toShort())
        buffer.putInt(value.toInt())
        return this
    }

    fun putUShort(value: UShort): IsoTypeBuffer {
        buffer.putShort(value.toShort())
        return this
    }

    fun putUShort(value: Int): IsoTypeBuffer {
        buffer.putShort(value.toShort())
        return this
    }

    fun putBytes(buffer: ByteBuffer): IsoTypeBuffer {
        this.buffer.put(buffer)
        return this
    }

    private fun condition() {
        if (position == buffer.position()) return
        bitPosition = 8
        position = buffer.position()
        currentUByte = buffer.get(position).toUByte()
    }

    private fun next() {
        if (bitPosition == 0) {
            bitPosition = 8
            position++
            if (1 < buffer.remaining()) {
                currentUByte = buffer.get(position).toUByte()
                buffer.position(position)
            }
        }
    }

    companion object {
        const val BIT8: UByte = 0b10000000u
        const val BIT7: UByte = 0b01000000u
        const val BIT6: UByte = 0b00100000u
        const val BIT5: UByte = 0b00010000u
        const val BIT4: UByte = 0b00001000u
        const val BIT3: UByte = 0b00000100u
        const val BIT2: UByte = 0b00000010u
        const val BIT1: UByte = 0b00000001u

        const val MASK8: UByte = 0b11111111u
        const val MASK7: UByte = 0b01111111u
        const val MASK6: UByte = 0b00111111u
        const val MASK5: UByte = 0b00011111u
        const val MASK4: UByte = 0b00001111u
        const val MASK3: UByte = 0b00000111u
        const val MASK2: UByte = 0b00000011u
        const val MASK1: UByte = 0b00000001u
    }
}
