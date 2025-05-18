package com.haishinkit.rtmp.util

import junit.framework.TestCase
import java.net.URI

class UriUtilTest : TestCase() {
    fun testGetCompositeTime() {
        val uri = URI.create("rtmp://user:password@localhost:443/live")
        assertEquals(UriUtil.withoutUserInfo(uri), "rtmp://localhost:443/live")
    }
}
