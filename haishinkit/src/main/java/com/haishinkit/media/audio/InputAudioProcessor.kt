package com.haishinkit.media.audio

import java.nio.ByteBuffer

/** A per-binding guard: pushed inputs may finish a callback concurrently with stop. */
internal class InputAudioProcessor(private val factory: AudioProcessor.Factory?) : AutoCloseable {
    private var processor: AudioProcessor? = null
    private var closed = false

    @Synchronized fun append(
        input: ByteBuffer,
        format: PcmAudioFormat,
        output: (ByteBuffer) -> Unit,
    ) {
        if (closed) return
        if (factory == null) {
            output(input)
            return
        }
        if (processor?.format != format) {
            val old = processor
            processor = null
            old?.close()
            val next = factory.create(format)
            if (next.format != format) {
                next.close()
                throw IllegalArgumentException("Audio processor must preserve the input format")
            }
            processor = next
        }
        val result = requireNotNull(processor).process(input)
        val frameBytes = format.channels * if (format.encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        require(result.remaining() % frameBytes == 0) { "Audio processor returned an incomplete PCM frame" }
        if (result.hasRemaining()) output(result)
    }

    @Synchronized fun reset() {
        if (!closed) processor?.reset()
    }

    @Synchronized override fun close() {
        closed = true
        val old = processor
        processor = null
        old?.close()
    }
}
