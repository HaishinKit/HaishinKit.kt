package com.haishinkit.codec

import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * An additional key-value option applied to an Android [MediaFormat].
 *
 * @property key The media format key.
 * @property value An `Int`, `Long`, `Float`, `String`, or [ByteBuffer]. Other types cause an
 * `IllegalArgumentException` when the option is applied.
 */
data class CodecOption(
    val key: String,
    val value: Any,
) {
    internal fun apply(format: MediaFormat) {
        when (true) {
            (value is Int) -> format.setInteger(key, value)
            (value is Long) -> format.setLong(key, value)
            (value is Float) -> format.setFloat(key, value)
            (value is String) -> format.setString(key, value)
            (value is ByteBuffer) -> format.setByteBuffer(key, value)
            else -> throw IllegalArgumentException()
        }
    }
}
