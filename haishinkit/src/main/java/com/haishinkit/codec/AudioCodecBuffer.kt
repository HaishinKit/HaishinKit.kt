package com.haishinkit.codec

import androidx.core.util.Pools
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque

internal class AudioCodecBuffer {
    var sampleRate: Int = 44100
    var channelCount: Int = 1
    var presentationTimestamp: Long = DEFAULT_PRESENTATION_TIMESTAMP
        private set
    private val pool = Pools.SynchronizedPool<PcmBlock>(CAPACITY * 2)
    private val buffers = LinkedBlockingDeque<PcmBlock>(CAPACITY)
    private var pending: PcmBlock? = null
    private var anchorTimeUs = DEFAULT_PRESENTATION_TIMESTAMP
    private var anchorFrame = 0L
    private var nextByteOffset = 0L

    fun append(byteBuffer: ByteBuffer) {
        val block = pool.acquire() ?: PcmBlock(ByteBuffer.allocateDirect(byteBuffer.remaining()))
        if (block.buffer.capacity() < byteBuffer.remaining()) block.buffer = ByteBuffer.allocateDirect(byteBuffer.remaining())
        val buffer = block.buffer
        buffer.clear()
        buffer.put(byteBuffer.duplicate())
        buffer.flip()
        block.byteOffset = nextByteOffset
        nextByteOffset += buffer.remaining()
        if (buffers.remainingCapacity() == 0) buffers.poll()?.let { pool.release(it) }
        buffers.add(block)
    }

    fun render(byteBuffer: ByteBuffer): Int? {
        val block = pending ?: buffers.poll() ?: return null
        val buffer = block.buffer
        val frame = (block.byteOffset + buffer.position()) / (2 * channelCount)
        val count = minOf(buffer.remaining(), byteBuffer.remaining())
        val view = buffer.duplicate()
        view.limit(view.position() + count)
        byteBuffer.put(view)
        buffer.position(buffer.position() + count)
        if (buffer.hasRemaining()) {
            pending = block
        } else {
            pending = null
            pool.release(block)
        }
        if (anchorTimeUs == DEFAULT_PRESENTATION_TIMESTAMP) {
            anchorTimeUs = System.nanoTime() / 1000
            anchorFrame = frame
        }
        // Queue eviction leaves a gap in frame positions, including after a partial block.
        presentationTimestamp = anchorTimeUs + (frame - anchorFrame) * 1_000_000 / sampleRate
        return count
    }

    fun clear() {
        pending?.let { pool.release(it) }
        pending = null
        while (true) pool.release(buffers.poll() ?: break)
        presentationTimestamp = DEFAULT_PRESENTATION_TIMESTAMP
        anchorTimeUs = DEFAULT_PRESENTATION_TIMESTAMP
        anchorFrame = 0
        nextByteOffset = 0
    }

    private class PcmBlock(var buffer: ByteBuffer, var byteOffset: Long = 0)

    companion object {
        const val CAPACITY = 4
        const val DEFAULT_PRESENTATION_TIMESTAMP = 0L
    }
}
