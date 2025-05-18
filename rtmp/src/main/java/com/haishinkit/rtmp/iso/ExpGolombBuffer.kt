package com.haishinkit.rtmp.iso

import com.haishinkit.util.toPositiveInt
import java.nio.ByteBuffer
import java.util.BitSet
import kotlin.math.pow

/**
 * The ExpGolomb represents Exponential-Golomb coding
 * @see
 *  https://en.wikipedia.org/wiki/Exponential-Golomb_coding
 */
internal class ExpGolombBuffer(
    private val bitSet: BitSet,
) {
    companion object {
        fun wrap(buffer: ByteBuffer): ExpGolombBuffer {
            val bitSet = BitSet(buffer.remaining() * 8)
            var count = 1
            while (buffer.hasRemaining()) {
                val data = buffer.get().toPositiveInt()
                val radix = 8 * (count - 1)
                bitSet.set(0 + radix, (data shr 7 and 1) == 1)
                bitSet.set(1 + radix, (data shr 6 and 1) == 1)
                bitSet.set(2 + radix, (data shr 5 and 1) == 1)
                bitSet.set(3 + radix, (data shr 4 and 1) == 1)
                bitSet.set(4 + radix, (data shr 3 and 1) == 1)
                bitSet.set(5 + radix, (data shr 2 and 1) == 1)
                bitSet.set(6 + radix, (data shr 1 and 1) == 1)
                bitSet.set(7 + radix, (data shr 0 and 1) == 1)
                ++count
            }
            return ExpGolombBuffer(bitSet)
        }
    }

    var position: Int = 0
        private set

    val boolean: Boolean get() = bitSet.get(position++)

    val int: Int
        get() {
            var bitCount = 0
            for (i in position until bitSet.length()) {
                if (bitSet.get(i)) {
                    break
                }
                ++position
                ++bitCount
            }

            if (bitCount == 0) {
                ++position
                return 0
            }

            var byte = 0
            for (i in position..position + bitCount) {
                if (bitSet.get(i)) {
                    byte += 2f.toDouble().pow(bitCount.toDouble()).toInt()
                }
                ++position
                --bitCount
            }

            return --byte
        }

    override fun toString(): String = bitSet.toString()
}
