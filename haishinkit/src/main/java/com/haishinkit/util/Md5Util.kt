package com.haishinkit.util

import android.util.Base64
import java.security.MessageDigest
import kotlin.text.Charsets.UTF_8

internal object Md5Util {
    fun base64(
        str: String,
        flags: Int,
    ): String = Base64.encodeToString(md5(str), flags)

    private fun md5(str: String): ByteArray = MessageDigest.getInstance("MD5").digest(str.toByteArray(UTF_8))
}
