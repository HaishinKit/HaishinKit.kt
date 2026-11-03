package com.haishinkit.app

import org.junit.Assert.assertEquals
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
}
