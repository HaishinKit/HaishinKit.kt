package com.haishinkit.util

import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream

internal object DataUriUtil {
    fun encode(
        bitmap: Bitmap,
        quality: Int = 90,
    ): String {
        val bytes = encodeToBytes(bitmap, quality)
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/webp;base64,$base64"
    }

    private fun encodeToBytes(
        bitmap: Bitmap,
        quality: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val format =
            if (Build.VERSION.SDK_INT >= 30) {
                if (bitmap.hasAlpha()) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    Bitmap.CompressFormat.WEBP_LOSSY
                }
            } else {
                Bitmap.CompressFormat.WEBP
            }
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }
}
