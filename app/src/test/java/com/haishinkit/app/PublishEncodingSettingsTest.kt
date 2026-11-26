package com.haishinkit.app

import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
