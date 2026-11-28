package com.haishinkit.app

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class PublishEncodingSettingsTest {
    @Test fun hlgStagesMain10AndBitrateBeforeACameraBecomesAvailable() {
        for (choice in listOf(PublishVideoCodec.H265, PublishVideoCodec.AV1)) {
            ShadowMediaCodecList.reset()
            val caps =
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(MediaFormat.createVideoFormat(choice.profile.mime, 320, 180))
                    .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
                    .setIsEncoder(true).setProfileLevels(emptyArray()).build()
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.video.encoder").setIsEncoder(true).setCapabilities(caps).build())
            try {
                val video = VideoCodec.Setting()
                val settings = PublishEncodingSettings(videoCodec = choice, videoBitRate = 2_000_000)
                settings.validateAndApply(video, AudioCodec.Setting(), PublishingProtocol.RTMP, videoEnabled = false, audioEnabled = false, range = com.haishinkit.graphics.DynamicRangeMode.HLG)
                assertEquals(2, video.profileLevel.profile)
                assertEquals(choice.profile.mime, video.profileLevel.mime)
                assertEquals(2_000_000, video.bitRate)
                // The fake encoder has no Main10 profiles: staging must not validate an inactive input.
                assertEquals(2, settings.createVideoFormat(video, PublishingProtocol.RTMP, com.haishinkit.graphics.DynamicRangeMode.HLG).getInteger(MediaFormat.KEY_PROFILE))
            } finally {
                ShadowMediaCodecList.reset()
            }
        }
    }

    @Test fun hlgAudioOnlyDoesNotRequireTheSelectedVideoEncoderOrBitrate() {
        ShadowMediaCodecList.reset()
        val caps =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1))
                .setIsEncoder(true).setProfileLevels(emptyArray()).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.aac.encoder").setIsEncoder(true).setCapabilities(caps).build())
        try {
            val video = VideoCodec.Setting()
            PublishEncodingSettings(videoCodec = PublishVideoCodec.H265, videoBitRate = -1)
                .validateAndApply(video, AudioCodec.Setting(), PublishingProtocol.RTMP, videoEnabled = false, range = com.haishinkit.graphics.DynamicRangeMode.HLG)
            assertEquals(VideoCodec.DEFAULT_PROFILE_LEVEL, video.profileLevel)
            assertEquals(VideoCodec.DEFAULT_BIT_RATE, video.bitRate)
        } finally {
            ShadowMediaCodecList.reset()
        }
    }

    @Test fun hlgUsesMain10Bt2020LimitedRangeForBothCodecs() {
        for (codec in listOf(PublishVideoCodec.H265, PublishVideoCodec.AV1)) {
            val settings = PublishEncodingSettings(videoCodec = codec)
            val format = settings.createVideoFormat(VideoCodec.Setting(), PublishingProtocol.RTMP, com.haishinkit.graphics.DynamicRangeMode.HLG)
            assertEquals(codec.profile.mime, format.getString(MediaFormat.KEY_MIME))
            assertEquals(2, format.getInteger(MediaFormat.KEY_PROFILE))
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
            assertEquals(MediaFormat.COLOR_TRANSFER_HLG, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
            assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
            val sdr = settings.createVideoFormat(VideoCodec.Setting(), PublishingProtocol.RTMP)
            org.junit.Assert.assertFalse(sdr.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
        }
    }

    @Test fun hlgRejectsOtherCodecsAndTransportsBeforeApplyingSettings() {
        for (codec in listOf(PublishVideoCodec.H264, PublishVideoCodec.VP8, PublishVideoCodec.VP9)) {
            assertThrows(IllegalArgumentException::class.java) {
                PublishEncodingSettings(videoCodec = codec).createVideoFormat(VideoCodec.Setting(), PublishingProtocol.RTMP, com.haishinkit.graphics.DynamicRangeMode.HLG)
            }
        }
        for (protocol in listOf(PublishingProtocol.SRT, PublishingProtocol.WEBRTC)) {
            assertThrows(IllegalArgumentException::class.java) {
                PublishEncodingSettings(videoCodec = PublishVideoCodec.H265).createVideoFormat(VideoCodec.Setting(), protocol, com.haishinkit.graphics.DynamicRangeMode.HLG)
            }
        }
    }

    @Test fun hlgDefaultChangesOnlyAnIncompatibleVideoCodec() {
        val original = PublishEncodingSettings(audioCodec = PublishAudioCodec.OPUS, videoBitRate = 1_500_000)
        val hlg = original.forDynamicRange(com.haishinkit.graphics.DynamicRangeMode.HLG)
        assertEquals(original.copy(videoCodec = PublishVideoCodec.H265), hlg)
        val av1 = original.copy(videoCodec = PublishVideoCodec.AV1)
        assertEquals(av1, av1.forDynamicRange(com.haishinkit.graphics.DynamicRangeMode.HLG))
        assertEquals(original, original.forDynamicRange(com.haishinkit.graphics.DynamicRangeMode.SDR))
    }

    @Test
    fun bitrateUpdateKeepsRunningAacAndOpusEncodersUntouched() {
        for (choice in PublishAudioCodec.entries) {
            val codec = AudioCodec()
            val audio = AudioCodec.Setting(codec).apply { mimeType = choice.mime }
            val video = VideoCodec.Setting()
            codec.isRunning.set(true)
            try {
                // Verify that the fixture rejects the write which previously blocked bitrate changes.
                assertThrows(IllegalStateException::class.java) { audio.mimeType = choice.mime }
                val settings = PublishEncodingSettings(audioCodec = choice, videoBitRate = 2_000_000)
                settings.applyCodecSettings(video, audio, VideoCodec.DEFAULT_PROFILE_LEVEL)
                assertEquals(2_000_000, video.bitRate)
                assertEquals(choice.mime, audio.mimeType)
            } finally {
                codec.isRunning.set(false)
                codec.dispose()
            }
        }
    }

    @Test
    fun srtBitrateChangePreservesTheRequestedRtmpCodecs() {
        val requested = PublishEncodingSettings(PublishVideoCodec.VP9, PublishAudioCodec.OPUS)
        val effective = requested.forProtocol(PublishingProtocol.SRT)
        val updated = requested.mergingChanges(effective, effective.copy(videoBitRate = 2_000_000))
        assertEquals(PublishVideoCodec.VP9, updated.videoCodec)
        assertEquals(PublishAudioCodec.OPUS, updated.audioCodec)
        assertEquals(2_000_000, updated.videoBitRate)
        val changedVideo = requested.mergingChanges(effective, effective.copy(videoCodec = PublishVideoCodec.H265))
        assertEquals(PublishVideoCodec.H265, changedVideo.videoCodec)
        assertEquals(PublishAudioCodec.OPUS, changedVideo.audioCodec)
    }

    @Test
    fun audioOnlyPreparationDoesNotRequireAVideoEncoderOrValidVideoBitrate() {
        ShadowMediaCodecList.reset()
        val capabilities =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1))
                .setIsEncoder(true).setProfileLevels(emptyArray()).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.aac.encoder").setIsEncoder(true).setCapabilities(capabilities).build())
        val video = VideoCodec.Setting()
        val audio = AudioCodec.Setting()
        PublishEncodingSettings(videoCodec = PublishVideoCodec.AV1, videoBitRate = -1).validateAndApply(video, audio, PublishingProtocol.RTMP, videoEnabled = false)
        assertEquals(VideoCodec.DEFAULT_BIT_RATE, video.bitRate)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, audio.mimeType)
    }

    @Test
    fun whipProbeUsesTheConstrainedBaselineProfileRequestedByRtc() {
        val format = PublishEncodingSettings().createVideoFormat(VideoCodec.Setting(), PublishingProtocol.WEBRTC)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, format.getInteger(MediaFormat.KEY_PROFILE))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31, format.getInteger(MediaFormat.KEY_LEVEL))
    }

    @Test fun whipKeepsVpCodecsAndDoesNotApplyAnAvcProfile() {
        for (codec in listOf(PublishVideoCodec.VP8, PublishVideoCodec.VP9)) {
            val settings = PublishEncodingSettings(videoCodec = codec).forProtocol(PublishingProtocol.WEBRTC)
            assertEquals(codec, settings.videoCodec)
            assertEquals(PublishAudioCodec.OPUS, settings.audioCodec)
            val format = settings.createVideoFormat(VideoCodec.Setting(), PublishingProtocol.WEBRTC)
            assertEquals(codec.profile.mime, format.getString(MediaFormat.KEY_MIME))
            assertEquals(codec.profile.profile, format.getInteger(MediaFormat.KEY_PROFILE))
        }
        assertEquals(PublishVideoCodec.H264, PublishEncodingSettings(videoCodec = PublishVideoCodec.AV1).forProtocol(PublishingProtocol.WEBRTC).videoCodec)
    }
}
