package com.haishinkit.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.haishinkit.codec.Codec
import com.haishinkit.codec.CodecCapabilities
import com.haishinkit.codec.VideoCodec
import com.haishinkit.codec.VideoCodecProfileLevel
import com.haishinkit.stream.Stream

enum class RtmpVideoCodec(val label: String, val profile: VideoCodecProfileLevel) {
    H264("H.264", VideoCodecProfileLevel.H264_BASELINE_3_2),
    H265("H.265", VideoCodecProfileLevel.HEVC_MAIN_3_1),
    AV1("AV1", VideoCodecProfileLevel.AV1_MAIN8_3_1),
    VP8("VP8", VideoCodecProfileLevel.VP8_MAIN),
    VP9("VP9", VideoCodecProfileLevel.VP9_PROFILE0_31),
}

enum class RtmpAudioCodec(val label: String, val mime: String) {
    AAC("AAC", MediaFormat.MIMETYPE_AUDIO_AAC),
    OPUS("Opus", MediaFormat.MIMETYPE_AUDIO_OPUS),
}

data class RtmpEncodingSettings(
    val videoCodec: RtmpVideoCodec = RtmpVideoCodec.H264,
    val audioCodec: RtmpAudioCodec = RtmpAudioCodec.AAC,
    val videoBitRate: Int = VideoCodec.DEFAULT_BIT_RATE,
) {
    fun applyTo(stream: Stream) {
        require(videoBitRate in MIN_VIDEO_BIT_RATE..MAX_VIDEO_BIT_RATE)
        require(CodecCapabilities.isCodecSupportedByType(Codec.MODE_ENCODE, videoCodec.profile.mime)) { "No ${videoCodec.label} encoder is available on this device." }
        require(CodecCapabilities.isCodecSupportedByType(Codec.MODE_ENCODE, audioCodec.mime)) { "No ${audioCodec.label} encoder is available on this device." }
        val format =
            MediaFormat.createVideoFormat(videoCodec.profile.mime, stream.videoSetting.width, stream.videoSetting.height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, stream.videoSetting.frameRate)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PROFILE, videoCodec.profile.profile)
                setInteger(MediaFormat.KEY_LEVEL, videoCodec.profile.level)
            }
        require(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null) {
            "No ${videoCodec.label} encoder supports this resolution, frame rate and bitrate. Try a lower bitrate or another codec."
        }
        stream.videoSetting.profileLevel = videoCodec.profile
        stream.audioSetting.mimeType = audioCodec.mime
        stream.videoSetting.bitRate = videoBitRate
    }

    companion object {
        const val MIN_VIDEO_BIT_RATE = 100_000
        const val MAX_VIDEO_BIT_RATE = 12_000_000
    }
}
