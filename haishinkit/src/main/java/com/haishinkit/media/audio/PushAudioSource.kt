package com.haishinkit.media.audio

import java.nio.ByteBuffer

/** Decoder-owned PCM is consumed synchronously before its codec buffer is released. */
internal interface PcmAudioConsumer {
    fun append(
        buffer: ByteBuffer,
        format: PcmAudioFormat,
    )

    fun clear()
}

internal interface PushAudioSource {
    var consumer: PcmAudioConsumer?
    var onEnded: (() -> Unit)?
    val isClosed: Boolean
}
