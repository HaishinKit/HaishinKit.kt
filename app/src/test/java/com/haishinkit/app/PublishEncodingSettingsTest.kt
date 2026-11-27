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
}
