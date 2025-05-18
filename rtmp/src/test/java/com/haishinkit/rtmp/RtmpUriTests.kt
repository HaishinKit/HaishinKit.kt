package com.haishinkit.rtmp

import androidx.core.net.toUri
import junit.framework.TestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RtmpUriTests : TestCase() {
    @Test
    fun main() {
        val uri = RtmpUri("rtmp://localhost/live/live".toUri())
        assertEquals(uri.tcUrl, "rtmp://localhost/live")
        assertEquals(uri.streamName, "live")
    }
}
