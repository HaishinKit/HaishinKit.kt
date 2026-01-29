package com.haishinkit.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * An object that manages offscreen rendering an image source.
 */
open class ImageScreenObject : ScreenObject() {
    private interface Keys {
        companion object {
            const val BITMAP: String = "bitmap"
        }
    }

    override val type: String = TYPE
    var bitmap: Bitmap? = null
        set(value) {
            if (field == value) return
            field?.recycle()
            field = value
            invalidateLayout()
        }

    override var elements: Map<String, String>
        get() {
            return buildMap {
                put(Keys.BITMAP, bitmap?.toBase64() ?: "")
            }
        }
        set(value) {
            bitmap = value[Keys.BITMAP]?.toBitmap()
        }

    init {
        matrix[5] = matrix[5] * -1
    }

    companion object {
        const val TYPE: String = "image"

        private val TAG = VideoScreenObject::class.java.simpleName
    }
}

private fun String.toBitmap(): Bitmap {
    val bytes = Base64.decode(this, Base64.NO_WRAP)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun Bitmap.toBase64(
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    quality: Int = 100,
): String {
    val outputStream = ByteArrayOutputStream()
    compress(format, quality, outputStream)
    val bytes = outputStream.toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}
