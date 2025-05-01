package com.haishinkit.iso

import android.media.MediaFormat
import android.util.Size
import com.haishinkit.codec.CodecOption
import com.haishinkit.codec.VideoCodec
import java.nio.ByteBuffer

internal interface DecoderConfigurationRecord {
    val mime: String
    val capacity: Int
    val videoSize: Size?

    fun encode(buffer: ByteBuffer): DecoderConfigurationRecord

    fun configure(codec: VideoCodec): Boolean {
        codec.inputMimeType = mime
        codec.options = toCodecSpecificData(codec.options)
        return true
    }

    fun toByteBuffer(): ByteBuffer =
        ByteBuffer.allocate(capacity).apply {
            encode(this)
            flip()
        }

    fun toCodecSpecificData(options: List<CodecOption>): List<CodecOption>

    companion object {
        fun create(
            mime: String,
            mediaFormat: MediaFormat,
        ): DecoderConfigurationRecord? =
            when (mime) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> AvcDecoderConfigurationRecord.create(mediaFormat)
                MediaFormat.MIMETYPE_VIDEO_HEVC -> HevcDecoderConfigurationRecord.create(mediaFormat)
                else -> null
            }

        fun decode(
            mime: String,
            buffer: ByteBuffer,
        ): DecoderConfigurationRecord? =
            when (mime) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> AvcDecoderConfigurationRecord.decode(buffer)
                MediaFormat.MIMETYPE_VIDEO_HEVC -> HevcDecoderConfigurationRecord.decode(buffer)
                else -> null
            }
    }
}
