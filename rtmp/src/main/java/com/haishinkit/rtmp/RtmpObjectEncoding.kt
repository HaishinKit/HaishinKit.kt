package com.haishinkit.rtmp

import com.haishinkit.rtmp.message.RtmpMessage

internal enum class RtmpObjectEncoding(
    val rawValue: Short,
    val dataType: Byte,
    val commandType: Byte,
) {
    AMF0(
        0,
        RtmpMessage.TYPE_AMF0_DATA,
        RtmpMessage.TYPE_AMF0_COMMAND,
    ),
}
