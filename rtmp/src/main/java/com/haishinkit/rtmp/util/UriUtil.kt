package com.haishinkit.rtmp.util

import java.net.URI

internal object UriUtil {
    fun withoutUserInfo(uri: URI): String {
        val userInfo = uri.userInfo ?: return uri.toString()
        return uri.toString().replace("${uri.rawUserInfo}@", "")
    }
}
