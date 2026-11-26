package com.haishinkit.app

import com.haishinkit.stream.StreamSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PreferenceTest {
    @Test
    fun srtPreservesCompleteUriAndIgnoresRtmpStreamName() {
        val url = "srt://192.168.1.10:9710?mode=caller&streamid=%23!::r=live,m=publish&latency=120000"
        assertEquals(url, Preference(url, "ignored").toStreamUri().toString())
    }

    @Test
    fun listenerUriPreservesEmptyHost() {
        val url = "srt://:9710?mode=listener"
        assertEquals(url, Preference(url, "ignored").toStreamUri().toString())
    }

    @Test
    fun rtmpStillAppendsStreamName() {
        assertEquals("rtmp://example.com/live/test", Preference("rtmp://example.com/live", "test").toStreamUri().toString())
        assertEquals("rtmps://example.com/live/test", Preference("rtmps://example.com/live", "test").toStreamUri().toString())
    }

    @Test
    fun whipAndWhepUseTheirCompleteEndpointWithoutAnRtmpStreamName() {
        val publish = "https://example.com/whip?app=live&stream=demo"
        val playback = "https://example.com/whep?app=live&stream=demo"
        val preference = Preference(publish, "ignored", whepURL = playback)
        assertEquals(publish, preference.toStreamUri().toString())
        assertEquals(playback, preference.toStreamUri(StreamSession.Mode.PLAYBACK).toString())
    }

    @Test
    fun rtcSettingsSnapshotIncludesAuthenticationAndIceServers() {
        val preference = Preference("http://example.com/whip", "ignored", whepURL = "http://example.com/whep", bearerToken = " secret ", iceServers = "stun:example.com:3478\n\n turn:user:password@example.com:3478 ")
        val first = preference.sessionSettings()
        assertEquals("secret", first.rtc!!.bearerToken)
        assertEquals(listOf("stun:example.com:3478", "turn:user:password@example.com:3478"), first.rtc.iceServers)
        preference.bearerToken = "replacement"
        assertNotEquals(first, preference.sessionSettings())
        assertEquals("secret", first.rtc.bearerToken)
        assertEquals(StreamSession.Mode.PLAYBACK, preference.sessionSettings(StreamSession.Mode.PLAYBACK).mode)
    }

    @Test
    fun whepRequiresItsOwnHttpEndpointAndDoesNotAcceptAnRtmpUrl() {
        val preference = Preference("https://example.com/whip", "ignored")
        assertThrows(IllegalArgumentException::class.java) { preference.toStreamUri(StreamSession.Mode.PLAYBACK) }
        preference.whepURL = "rtmp://example.com/live"
        assertThrows(IllegalArgumentException::class.java) { preference.toStreamUri(StreamSession.Mode.PLAYBACK) }
        preference.whepURL = "https:///missing-host"
        assertThrows(IllegalArgumentException::class.java) { preference.toStreamUri(StreamSession.Mode.PLAYBACK) }
    }

    @Test
    fun encodingSnapshotRetainsSelectionAndConformsToTransportWithoutChangingPlayback() {
        val preference = Preference("rtmps://example.com/live", "test")
        val original = preference.sessionSettings()
        val selected = PublishEncodingSettings(PublishVideoCodec.VP9, PublishAudioCodec.OPUS, 2_500_000)
        preference.encoding = selected
        val next = preference.sessionSettings()
        assertNotEquals(original, next)
        assertEquals(PublishEncodingSettings(), original.encoding)
        assertEquals(selected, next.encoding)
        assertEquals(selected, preference.copy(streamURL = "rtmp://example.net/live").sessionSettings().encoding)
        assertNull(preference.sessionSettings(StreamSession.Mode.PLAYBACK).encoding)
        assertEquals(PublishVideoCodec.H265, selected.copy(videoCodec = PublishVideoCodec.H265).forProtocol(PublishingProtocol.SRT).videoCodec)
        assertEquals(PublishVideoCodec.H264, preference.copy(streamURL = "srt://example.com:9710").sessionSettings().encoding!!.videoCodec)
        assertEquals(PublishAudioCodec.AAC, preference.copy(streamURL = "srt://example.com:9710").sessionSettings().encoding!!.audioCodec)
        assertEquals(PublishAudioCodec.OPUS, preference.copy(streamURL = "https://example.com/whip").sessionSettings().encoding!!.audioCodec)
    }

    @Test
    fun unsupportedEncodingDoesNotPreventSessionCreationForPreviewOrSharing() =
        kotlinx.coroutines.runBlocking {
            org.robolectric.shadows.ShadowMediaCodecList.reset()
            StreamSession.Builder.registerFactory(com.haishinkit.rtmp.RtmpStreamSessionFactory)
            val settings = Preference("rtmp://example.com/live", "test", encoding = PublishEncodingSettings(videoCodec = PublishVideoCodec.AV1, videoBitRate = -1)).sessionSettings()
            val session = settings.create(org.robolectric.RuntimeEnvironment.getApplication())
            try {
                assertEquals(StreamSession.ReadyState.CLOSED, session.readyState.value)
            } finally {
                session.close()
            }
        }
}
