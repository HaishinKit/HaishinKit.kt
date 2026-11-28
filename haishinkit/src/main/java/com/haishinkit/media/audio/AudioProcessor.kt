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

    /** Creates a new, exclusively owned processor whenever the track starts or changes format. */
    fun interface Factory {
        fun create(format: PcmAudioFormat): AudioProcessor
    }
}
