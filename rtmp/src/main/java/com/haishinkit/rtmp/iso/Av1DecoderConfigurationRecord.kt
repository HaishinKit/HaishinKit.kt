package com.haishinkit.rtmp.iso

import android.media.MediaFormat
import android.util.Size
import com.haishinkit.codec.CodecOption
import java.nio.ByteBuffer

/** AV1CodecConfigurationRecord, AV1 ISO media file format binding section 2.3. */
internal class Av1DecoderConfigurationRecord private constructor(
    private val bytes: ByteArray,
) : DecoderConfigurationRecord {
    override val mime: String = MediaFormat.MIMETYPE_VIDEO_AV1
    override val capacity: Int
        get() = bytes.size
    override val videoSize: Size?
        get() = sequenceHeader()?.let(::readVideoSize)

    override fun encode(buffer: ByteBuffer): Av1DecoderConfigurationRecord {
        buffer.put(bytes)
        return this
    }

    override fun toCodecSpecificData(options: List<CodecOption>): List<CodecOption> =
        options.filter { it.key != CSD0 && it.key != "csd-1" } + CodecOption(CSD0, toByteBuffer())

    private fun sequenceHeader(): ByteBuffer? {
        val buffer = ByteBuffer.wrap(bytes, 4, bytes.size - 4).slice()
        while (buffer.hasRemaining()) {
            val header = buffer.get().toInt() and 0xFF
            require(header and 0x81 == 0) { "Invalid AV1 OBU header" }
            require(header and 2 != 0) { "AV1 configuration OBUs must have a size field" }
            if (header and 4 != 0) {
                require(buffer.hasRemaining()) { "Truncated AV1 OBU extension" }
                buffer.get()
            }
            var size = 0L
            var complete = false
            for (index in 0 until 8) {
                require(buffer.hasRemaining()) { "Truncated AV1 OBU size" }
                val value = buffer.get().toInt() and 0xFF
                size = size or ((value and 0x7F).toLong() shl (index * 7))
                if (value and 0x80 == 0) {
                    complete = true
                    break
                }
            }
            require(complete && size <= buffer.remaining()) { "Invalid AV1 OBU size" }
            val payload = buffer.slice().apply { limit(size.toInt()) }
            if (header shr 3 and 0x0F == 1) return payload
            buffer.position(buffer.position() + size.toInt())
        }
        return null
    }

    companion object {
        private const val CSD0 = "csd-0"

        fun decode(buffer: ByteBuffer): Av1DecoderConfigurationRecord {
            require(buffer.remaining() >= 4) { "Truncated AV1 configuration record" }
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            require(bytes[0].toInt() and 0xFF == 0x81) { "Unsupported AV1 configuration version" }
            require(bytes[3].toInt() and 0xE0 == 0) { "Invalid AV1 configuration reserved bits" }
            require(bytes[3].toInt() and 0x10 != 0 || bytes[3].toInt() and 0x0F == 0) {
                "Invalid AV1 presentation delay"
            }
            return Av1DecoderConfigurationRecord(bytes).also { it.videoSize }
        }

        private fun readVideoSize(buffer: ByteBuffer): Size {
            val bits = Bits(buffer)
            bits.read(3) // seq_profile
            bits.read(1) // still_picture
            val reduced = bits.read(1) != 0
            if (reduced) {
                bits.read(5) // seq_level_idx[0]
            } else {
                var decoderModel = false
                var delayBits = 0
                if (bits.read(1) != 0) { // timing_info_present_flag
                    bits.read(32)
                    bits.read(32)
                    if (bits.read(1) != 0) bits.uvlc()
                    decoderModel = bits.read(1) != 0
                    if (decoderModel) {
                        delayBits = bits.read(5) + 1
                        bits.read(32)
                        bits.read(5)
                        bits.read(5)
                    }
                }
                val initialDelay = bits.read(1) != 0
                repeat(bits.read(5) + 1) {
                    bits.read(12) // operating_point_idc
                    if (bits.read(5) > 7) bits.read(1) // seq_tier
                    if (decoderModel && bits.read(1) != 0) {
                        bits.read(delayBits)
                        bits.read(delayBits)
                        bits.read(1)
                    }
                    if (initialDelay && bits.read(1) != 0) bits.read(4)
                }
            }
            val widthBits = bits.read(4) + 1
            val heightBits = bits.read(4) + 1
            return Size(bits.read(widthBits) + 1, bits.read(heightBits) + 1)
        }
    }

    private class Bits(
        private val buffer: ByteBuffer,
    ) {
        private var byte = 0
        private var remaining = 0

        fun read(count: Int): Int {
            var result = 0
            repeat(count) {
                if (remaining == 0) {
                    require(buffer.hasRemaining()) { "Truncated AV1 sequence header" }
                    byte = buffer.get().toInt() and 0xFF
                    remaining = 8
                }
                result = (result shl 1) or (byte shr --remaining and 1)
            }
            return result
        }

        fun uvlc() {
            var leading = 0
            while (read(1) == 0) {
                leading++
                if (leading == 32) return
            }
            read(leading)
        }
    }
}
