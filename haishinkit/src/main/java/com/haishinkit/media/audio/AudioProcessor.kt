package com.haishinkit.media.audio

import java.nio.ByteBuffer

/** A stateful input-track processor. Buffers use [format] and are consumed synchronously.
 *
 * The mixer serializes calls, including [reset] after a discontinuity and [close] on stop.
 * Return a buffer valid until the next call. Preserve the sample rate/channel layout and
 * sample count over time; processors must not block on I/O or retain the input buffer.
 */
interface AudioProcessor : AutoCloseable {
    val format: PcmAudioFormat

    fun process(input: ByteBuffer): ByteBuffer

    fun reset()

    override fun close()

    /**
     * Creates a new, exclusively owned processor for a track start or a pushed decoder's
     * format change. Read-based sources keep their format fixed until detached/closed.
     */
    fun interface Factory {
        fun create(format: PcmAudioFormat): AudioProcessor
    }
}
