package com.haishinkit.util

import java.nio.ByteBuffer

fun ByteBuffer.toHexString(): String {
    val builder = StringBuilder()
    val newPosition = position()
    while (hasRemaining()) {
        builder.append("%02x".format(get()))
    }
    position(newPosition)
    return builder.toString()
}
