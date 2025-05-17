package com.haishinkit.media

import java.nio.ByteBuffer

data class MediaBuffer(
    val type: MediaType,
    var index: Int,
    var payload: ByteBuffer? = null,
    var timestamp: Long = 0L,
    var sync: Boolean = false,
)
