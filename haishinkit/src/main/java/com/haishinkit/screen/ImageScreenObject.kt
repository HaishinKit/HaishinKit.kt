package com.haishinkit.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import com.haishinkit.util.DataUriUtil

private sealed interface ImageSource {
    val uri: Uri

    fun toBitmap(): Bitmap

    class Data(override val uri: Uri) : ImageSource {
        override fun toBitmap(): Bitmap {
            val data = uri.schemeSpecificPart
            val base64 = data.substringAfter("base64,")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    companion object {
        fun parseImageSource(uri: Uri?): ImageSource =
            when (uri?.scheme) {
                "data" -> Data(uri)
                else -> error("unsupported")
            }
    }
}

/**
 * An object that manages offscreen rendering an image source.
 */
open class ImageScreenObject(
    id: String? = null,
) : ScreenObject(id) {
    private interface Keys {
        companion object {
            const val SOURCE: String = "source"
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

    private var source: String? = null
        get() {
            if (field == null && bitmap != null) {
                return DataUriUtil.encode(bitmap!!)
            }
            return field
        }
        set(value) {
            field = value
            runCatching {
                bitmap = ImageSource.parseImageSource(source?.toUri()).toBitmap()
            }
        }

    override var elements: Map<String, String>
        get() {
            return buildMap {
                put(Keys.SOURCE, source ?: "")
            }
        }
        set(value) {
            source = value[Keys.SOURCE]
        }

    init {
        matrix[5] = matrix[5] * -1
    }

    companion object {
        const val TYPE: String = "image"
    }
}
