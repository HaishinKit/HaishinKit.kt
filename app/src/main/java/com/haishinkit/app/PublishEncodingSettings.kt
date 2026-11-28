package com.haishinkit.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.Codec
import com.haishinkit.codec.CodecCapabilities
import com.haishinkit.codec.VideoCodec
import com.haishinkit.codec.VideoCodecProfileLevel
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.stream.Stream

enum class PublishingProtocol(val label: String) {
    RTMP("RTMP"),
    SRT("SRT"),
    WEBRTC("WHIP / WHEP"),
    ;

    companion object {
        fun fromUrl(url: String): PublishingProtocol =
            when (url.trim().substringBefore(':').lowercase()) {
                "srt" -> SRT
                "http", "https" -> WEBRTC
                else -> RTMP
            }
    }
}

enum class PublishVideoCodec(val label: String, val profile: VideoCodecProfileLevel) {
    H264("H.264", VideoCodecProfileLevel.H264_BASELINE_3_2),
    H265("H.265", VideoCodecProfileLevel.HEVC_MAIN_3_1),
    AV1("AV1", VideoCodecProfileLevel.AV1_MAIN8_3_1),
    VP8("VP8", VideoCodecProfileLevel.VP8_MAIN),
    VP9("VP9", VideoCodecProfileLevel.VP9_PROFILE0_31),
}

enum class PublishAudioCodec(val label: String, val mime: String) {
    AAC("AAC", MediaFormat.MIMETYPE_AUDIO_AAC),
    OPUS("Opus", MediaFormat.MIMETYPE_AUDIO_OPUS),
}

data class PublishEncodingSettings(
    val videoCodec: PublishVideoCodec = PublishVideoCodec.H264,
    val audioCodec: PublishAudioCodec = PublishAudioCodec.AAC,
    val videoBitRate: Int = VideoCodec.DEFAULT_BIT_RATE,
) {
    fun forProtocol(protocol: PublishingProtocol): PublishEncodingSettings =
        when (protocol) {
            PublishingProtocol.RTMP -> this
            PublishingProtocol.SRT -> copy(videoCodec = videoCodec.takeIf { it in listOf(PublishVideoCodec.H264, PublishVideoCodec.H265) } ?: PublishVideoCodec.H264, audioCodec = PublishAudioCodec.AAC)
            PublishingProtocol.WEBRTC -> copy(videoCodec = videoCodec.takeIf { it in listOf(PublishVideoCodec.H264, PublishVideoCodec.VP8, PublishVideoCodec.VP9) } ?: PublishVideoCodec.H264, audioCodec = PublishAudioCodec.OPUS)
        }

    fun forDynamicRange(range: DynamicRangeMode): PublishEncodingSettings =
        if (range == DynamicRangeMode.HLG && videoCodec !in listOf(PublishVideoCodec.H265, PublishVideoCodec.AV1)) copy(videoCodec = PublishVideoCodec.H265) else this

    internal fun videoProfile(
        protocol: PublishingProtocol,
        range: DynamicRangeMode,
    ): VideoCodecProfileLevel =
        if (range == DynamicRangeMode.HLG) {
            require(protocol == PublishingProtocol.RTMP) { "HLG publishing requires RTMP." }
            when (videoCodec) {
                PublishVideoCodec.H265 -> VideoCodecProfileLevel.HEVC_MAIN10_4_1
                PublishVideoCodec.AV1 -> VideoCodecProfileLevel.AV1_MAIN10_4_1
                else -> throw IllegalArgumentException("Select HEVC or AV1 for HLG publishing.")
            }
        } else if (protocol == PublishingProtocol.WEBRTC && videoCodec == PublishVideoCodec.H264) {
            VideoCodecProfileLevel.H264_BASELINE_3_1
        } else {
            videoCodec.profile
        }

    fun applyTo(
        stream: Stream,
        protocol: PublishingProtocol,
        validateFormat: Boolean = true,
        videoEnabled: Boolean = true,
        audioEnabled: Boolean = true,
    ) {
        validateAndApply(stream.videoSetting, stream.audioSetting, protocol, validateFormat, videoEnabled, audioEnabled, stream.screen.dynamicRangeMode)
    }

    internal fun validateAndApply(
        video: VideoCodec.Setting,
        audio: AudioCodec.Setting,
        protocol: PublishingProtocol,
        validateFormat: Boolean = true,
        videoEnabled: Boolean = true,
        audioEnabled: Boolean = true,
        range: DynamicRangeMode = DynamicRangeMode.SDR,
    ) {
        require(this == forProtocol(protocol)) { "The selected codecs are not supported by ${protocol.label}." }
        val profile = videoProfile(protocol, range)
        if (videoEnabled) {
            require(videoBitRate in MIN_VIDEO_BIT_RATE..MAX_VIDEO_BIT_RATE)
            require(CodecCapabilities.isCodecSupportedByType(Codec.MODE_ENCODE, profile.mime)) { "No ${videoCodec.label} encoder is available on this device." }
        }
        if (audioEnabled) require(CodecCapabilities.isCodecSupportedByType(Codec.MODE_ENCODE, audioCodec.mime)) { "No ${audioCodec.label} encoder is available on this device." }
        if (videoEnabled && validateFormat) {
            val format = createVideoFormat(video, protocol, range)
            if (range == DynamicRangeMode.HLG) {
                require(android.os.Build.VERSION.SDK_INT >= 33) { "HLG requires Android 13 or later." }
                require(
                    android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                        info.isEncoder && profile.mime in info.supportedTypes &&
                            info.getCapabilitiesForType(profile.mime).let { caps ->
                                (caps.isFeatureSupported("hdr-editing") || caps.isFeatureSupported("hlg-editing")) && caps.isFormatSupported(format)
                            }
                    },
                ) { "No encoder supports these 10-bit HLG settings. Select HEVC/AV1 or reduce the resolution/bitrate." }
            } else {
                // Match createEncoderByType used by the SDR library, rather than accepting a different implementation.

                val encoder = MediaCodec.createEncoderByType(profile.mime)
                try {
                    require(encoder.codecInfo.getCapabilitiesForType(profile.mime).isFormatSupported(format)) {
                        "The preferred ${videoCodec.label} encoder does not support this resolution, frame rate and bitrate. Try another codec or a lower bitrate."
                    }
                } finally {
                    encoder.release()
                }
            }
        }
        // A camera can appear while connect() is suspended. Stage HLG's Main10 settings
        // before that window, without requiring a video encoder for audio-only use.
        val stageVideo = videoEnabled || (range == DynamicRangeMode.HLG && CodecCapabilities.isCodecSupportedByType(Codec.MODE_ENCODE, profile.mime))
        applyCodecSettings(video, audio, profile, stageVideo, audioEnabled)
    }

    internal fun createVideoFormat(
        video: VideoCodec.Setting,
        protocol: PublishingProtocol,
        range: DynamicRangeMode = DynamicRangeMode.SDR,
    ): MediaFormat {
        val profile = videoProfile(protocol, range)
        return MediaFormat.createVideoFormat(profile.mime, video.width, video.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, video.frameRate)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PROFILE, if (protocol == PublishingProtocol.WEBRTC && videoCodec == PublishVideoCodec.H264) MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline else profile.profile)
            setInteger(MediaFormat.KEY_LEVEL, profile.level)
            if (range == DynamicRangeMode.HLG) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
    }

    internal fun applyCodecSettings(
        video: VideoCodec.Setting,
        audio: AudioCodec.Setting,
        profile: VideoCodecProfileLevel,
        videoEnabled: Boolean = true,
        audioEnabled: Boolean = true,
    ) {
        if (videoEnabled) {
            if (video.profileLevel != profile) video.profileLevel = profile
            video.bitRate = videoBitRate
        }
        if (audioEnabled && audio.mimeType != audioCodec.mime) audio.mimeType = audioCodec.mime
    }

    internal fun mergingChanges(
        previous: PublishEncodingSettings,
        next: PublishEncodingSettings,
    ): PublishEncodingSettings =
        copy(
            videoCodec = if (previous.videoCodec != next.videoCodec) next.videoCodec else videoCodec,
            audioCodec = if (previous.audioCodec != next.audioCodec) next.audioCodec else audioCodec,
            videoBitRate = if (previous.videoBitRate != next.videoBitRate) next.videoBitRate else videoBitRate,
        )

    companion object {
        const val MIN_VIDEO_BIT_RATE = 100_000
        const val MAX_VIDEO_BIT_RATE = 12_000_000
    }
}
