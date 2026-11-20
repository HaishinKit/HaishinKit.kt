package com.haishinkit.vqmetrix

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The same exact I420 bytes are stored and supplied to every encoder. */
data class ReferenceSpec(val width: Int, val height: Int, val fps: Int, val frames: Int) {
    val frameBytes: Int get() = width * height * 3 / 2

    init {
        require(width in 64..1920 && height in 64..1080 && width % 2 == 0 && height % 2 == 0)
        require(fps in 1..60 && frames in 1..600)
    }
}

object TestPattern {
    fun frame(
        spec: ReferenceSpec,
        index: Int,
    ): ByteArray {
        require(index >= 0)
        val result = ByteArray(spec.frameBytes)
        val w = spec.width
        val h = spec.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                val moving = (x + index * 7) % w
                val checker = ((x / 8 + y / 8 + index / 3) % 2) * 35
                val value = 16 + (moving * 170 / w + y * 14 / h + checker) % 220
                result[y * w + x] = value.toByte()
            }
        }
        val chroma = w * h / 4
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                result[w * h + y * w / 2 + x] = (96 + x * 64 / (w / 2)).toByte()
                result[w * h + chroma + y * w / 2 + x] = (96 + (y + index * 3) % 64).toByte()
            }
        }
        return result
    }
}

class Y4mWriter(file: File, val spec: ReferenceSpec, range: String = "limited") : AutoCloseable {
    private val output: OutputStream = BufferedOutputStream(file.outputStream(), 256 * 1024)
    private var count = 0

    init {
        val extension =
            if (range == "limited") {
                " XCOLORRANGE=LIMITED"
            } else if (range == "full") {
                " XCOLORRANGE=FULL"
            } else {
                ""
            }
        output.write("YUV4MPEG2 W${spec.width} H${spec.height} F${spec.fps}:1 Ip A1:1 C420jpeg$extension\n".toByteArray(Charsets.US_ASCII))
    }

    fun append(bytes: ByteArray) {
        require(bytes.size == spec.frameBytes && count < spec.frames)
        output.write("FRAME\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        count++
    }

    override fun close() = output.close()
}

class Y4mReader(file: File, val spec: ReferenceSpec) : AutoCloseable {
    private val input: InputStream = BufferedInputStream(file.inputStream(), 256 * 1024)
    private var count = 0

    init {
        val header = line()
        require(header.startsWith("YUV4MPEG2 ")) { "Invalid Y4M header" }
        val fields = header.split(' ')
        require(fields.contains("W${spec.width}") && fields.contains("H${spec.height}") && fields.contains("F${spec.fps}:1"))
        require(fields.contains("C420jpeg")) { "Unsupported Y4M format" }
    }

    fun next(): ByteArray? {
        if (count == spec.frames) return null
        require(line() == "FRAME") { "Missing reference frame $count" }
        val result = ByteArray(spec.frameBytes)
        var offset = 0
        while (offset < result.size) {
            val read = input.read(result, offset, result.size - offset)
            check(read > 0) { "Truncated reference frame $count" }
            offset += read
        }
        count++
        return result
    }

    override fun close() = input.close()

    private fun line(): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= 1024) {
            val value = input.read()
            check(value >= 0) { "Unexpected end of Y4M file" }
            if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII)
            bytes.add(value.toByte())
        }
        error("Y4M line is too long")
    }
}

data class BatchConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val seconds: Int,
    val bitrates: List<Int>,
    val codec: String,
    val camera: Boolean,
) {
    init {
        ReferenceSpec(width, height, fps, fps * seconds)
        require(seconds in 1..10 && fps in listOf(15, 30))
        require(bitrates.isNotEmpty() && bitrates.size <= 6 && bitrates.distinct().size == bitrates.size)
        require(bitrates.all { it in 50_000..20_000_000 })
        require(codec in listOf("h264", "hevc"))
    }

    companion object {
        fun parseBitrates(value: String): List<Int> {
            val fields = value.split(',')
            require(fields.size in 1..6)
            return fields.map {
                val number = it.trim().toBigDecimalOrNull() ?: error("Invalid bitrate")
                val bits = number.multiply(1_000_000.toBigDecimal()).intValueExact()
                require(bits in 50_000..20_000_000)
                bits
            }.also { require(it.distinct().size == it.size) }
        }
    }
}
