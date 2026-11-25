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
