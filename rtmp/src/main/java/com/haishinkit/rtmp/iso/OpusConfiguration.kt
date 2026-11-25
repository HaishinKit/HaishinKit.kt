package com.haishinkit.rtmp.iso

import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.CodecOption
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Single-stream OpusHead configuration; decoding remains in the shared AudioCodec. */
internal class OpusConfiguration private constructor(
    val channels: Int,
    val preSkip: Int,
    private val bytes: ByteArray,
) {
    fun toByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes.copyOf())

    fun apply(codec: AudioCodec) {
        codec.sampleRate = 48_000
        codec.channelCount = channels
        codec.options = codec.options.filterNot { it.key.startsWith("csd-") } +
            listOf(
                CodecOption("csd-0", toByteBuffer()),
                CodecOption("csd-1", longBuffer(preSkip * 1_000_000_000L / 48_000)),
                CodecOption("csd-2", longBuffer(80_000_000)),
            )
    }

    private fun longBuffer(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }

    companion object {
        /** Android encoder CSD may wrap OpusHead and a nanosecond codec delay. */
        fun fromEncoderCsd(
            buffer: ByteBuffer,
            codecDelay: ByteBuffer? = null,
        ): OpusConfiguration? {
            val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (data.remaining() < 8) return null
            val signature = ByteArray(8).also { data.duplicate().get(it) }.toString(Charsets.US_ASCII)
            var header: ByteArray? = null
            var delay: Long? = null
            if (signature == "OpusHead") {
                header = ByteArray(data.remaining()).also { data.get(it) }
            } else {
                if (signature != "AOPUSHDR") return null
                while (data.hasRemaining()) {
                    if (data.remaining() < 16) return null
                    val marker = ByteArray(8).also { data.get(it) }.toString(Charsets.US_ASCII)
                    val size = data.long
                    if (size < 0 || size > data.remaining().toLong()) return null
                    val end = data.position() + size.toInt()
                    when (marker) {
                        "AOPUSHDR" -> {
                            if (header != null || size < 19) return null
                            header = ByteArray(size.toInt()).also { data.get(it) }
                        }
                        "AOPUSDLY" -> {
                            if (size != 8L || delay != null) return null
                            delay = data.long
                        }
                    }
                    data.position(end)
                }
            }
            if (codecDelay != null) {
                val separate = codecDelay.duplicate().order(ByteOrder.nativeOrder())
                if (separate.remaining() != 8) return null
                delay = separate.long
            }
            val bytes = header ?: return null
            if (bytes.size < 19) return null
            if (delay != null) {
                // OpusHead uses 48-kHz samples even when the encoder's input rate differs.
                if (delay < 0 || delay > 2_000_000_000L) return null
                val skip = (delay * 48_000 + 500_000_000) / 1_000_000_000
                if (skip > 65535) return null
                bytes[10] = skip.toByte()
                bytes[11] = (skip shr 8).toByte()
            }
            return decode(ByteBuffer.wrap(bytes))
        }

        fun decode(buffer: ByteBuffer): OpusConfiguration? {
            val data = buffer.duplicate()
            if (data.remaining() < 19) return null
            val bytes = ByteArray(data.remaining()).also { data.get(it) }
            if (!bytes.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray(Charsets.US_ASCII))) return null
            val version = bytes[8].toInt() and 255
            val channels = bytes[9].toInt() and 255
            if (version !in 1..15 || channels !in 1..2 || bytes[18] != 0.toByte()) return null
            val preSkip = (bytes[10].toInt() and 255) or ((bytes[11].toInt() and 255) shl 8)
            return OpusConfiguration(channels, preSkip, bytes)
        }
    }
}
