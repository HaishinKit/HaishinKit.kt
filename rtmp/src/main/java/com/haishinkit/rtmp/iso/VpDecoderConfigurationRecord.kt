package com.haishinkit.rtmp.iso

import android.media.MediaFormat
import android.os.Build
import android.util.Size
import com.haishinkit.codec.CodecOption
import java.nio.ByteBuffer

/** VP configuration fields with the vpcC version/flags used by FFmpeg Enhanced RTMP. */
internal class VpDecoderConfigurationRecord private constructor(
    override val mime: String,
    private val bytes: ByteArray,
    override val videoSize: Size? = null,
) : DecoderConfigurationRecord {
    val profile: Int get() = bytes[0].toInt() and 0xFF
    val bitDepth: Int get() = (bytes[2].toInt() and 0xFF) ushr 4
    override val capacity: Int get() = bytes.size + 4

    fun withKeyFrameSize(frame: ByteBuffer): VpDecoderConfigurationRecord {
        val info = VpFrameHeader.read(mime, frame)
        require(info.profile == profile && info.bitDepth == bitDepth) { "VP keyframe does not match configuration" }
        return VpDecoderConfigurationRecord(mime, bytes, info.size)
    }

    override fun encode(buffer: ByteBuffer): VpDecoderConfigurationRecord {
        buffer.put(byteArrayOf(1, 0, 0, 0)).put(bytes)
        return this
    }

    override fun toCodecSpecificData(options: List<CodecOption>): List<CodecOption> {
        // Android VP8 needs no CSD; optional VP9 CodecPrivate is not an ISO vpcC record.
        val keys = setOf(MediaFormat.KEY_PROFILE, "color-standard", "color-transfer", "color-range")
        val result = options.filterNot { it.key.startsWith("csd-") || it.key in keys }.toMutableList()
        result.add(CodecOption(MediaFormat.KEY_PROFILE, 1 shl profile))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val standard =
                when (bytes[3].toInt() and 0xFF) {
                    1 -> MediaFormat.COLOR_STANDARD_BT709
                    5 -> MediaFormat.COLOR_STANDARD_BT601_PAL
                    6 -> MediaFormat.COLOR_STANDARD_BT601_NTSC
                    9 -> MediaFormat.COLOR_STANDARD_BT2020
                    else -> null
                }
            val transfer =
                when (bytes[4].toInt() and 0xFF) {
                    1, 6, 7 -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    8 -> MediaFormat.COLOR_TRANSFER_LINEAR
                    16 -> MediaFormat.COLOR_TRANSFER_ST2084
                    18 -> MediaFormat.COLOR_TRANSFER_HLG
                    else -> null
                }
            standard?.let { result.add(CodecOption(MediaFormat.KEY_COLOR_STANDARD, it)) }
            transfer?.let { result.add(CodecOption(MediaFormat.KEY_COLOR_TRANSFER, it)) }
            result.add(CodecOption(MediaFormat.KEY_COLOR_RANGE, if (bytes[2].toInt() and 1 != 0) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED))
        }
        return result
    }

    companion object {
        fun decode(
            mime: String,
            buffer: ByteBuffer,
        ): VpDecoderConfigurationRecord {
            require(mime == MediaFormat.MIMETYPE_VIDEO_VP8 || mime == MediaFormat.MIMETYPE_VIDEO_VP9)
            require(buffer.remaining() in setOf(8, 9, 12)) { "Invalid VP configuration length" }
            if (buffer.remaining() == 12) {
                require(buffer.get().toInt() == 1 && buffer.get().toInt() == 0 && buffer.get().toInt() == 0 && buffer.get().toInt() == 0) {
                    "Unsupported VP configuration version or flags"
                }
            }
            if (buffer.remaining() == 9) require(buffer.get().toInt() == 1) { "Unsupported VP configuration version" }
            val bytes = ByteArray(8).also { buffer.get(it) }
            val profile = bytes[0].toInt() and 0xFF
            val level = bytes[1].toInt() and 0xFF
            val depth = (bytes[2].toInt() and 0xFF) ushr 4
            val chroma = (bytes[2].toInt() ushr 1) and 7
            require(profile in 0..3 && (mime != MediaFormat.MIMETYPE_VIDEO_VP8 || profile == 0)) { "Invalid VP profile" }
            require(level in setOf(0, 10, 11, 20, 21, 30, 31, 40, 41, 50, 51, 52, 60, 61, 62)) { "Invalid VP level" }
            require(depth in setOf(8, 10, 12) && chroma in 0..3) { "Invalid VP color format" }
            require((profile < 2) == (depth == 8)) { "VP bit depth does not match profile" }
            require(if (profile % 2 == 0) chroma <= 1 else chroma >= 2) { "VP subsampling does not match profile" }
            require(bytes[5].toInt() != 0 || chroma == 3) { "RGB requires 4:4:4" }
            require(bytes[6].toInt() == 0 && bytes[7].toInt() == 0) { "VP8/VP9 initialization data must be empty" }
            return VpDecoderConfigurationRecord(mime, bytes)
        }

        /** Read only the uncompressed keyframe header; encoding/decoding remains in MediaCodec. */
        fun fromKeyFrame(
            mime: String,
            frame: ByteBuffer,
            level: Int = 0,
        ): VpDecoderConfigurationRecord {
            val info = VpFrameHeader.read(mime, frame)
            val bytes =
                byteArrayOf(
                    info.profile.toByte(),
                    level.toByte(),
                    ((info.bitDepth shl 4) or (info.chroma shl 1) or info.fullRange).toByte(),
                    info.primaries.toByte(),
                    info.transfer.toByte(),
                    info.matrix.toByte(),
                    0,
                    0,
                )
            return decode(mime, ByteBuffer.wrap(bytes)).let { VpDecoderConfigurationRecord(mime, bytes, info.size) }
        }
    }
}
