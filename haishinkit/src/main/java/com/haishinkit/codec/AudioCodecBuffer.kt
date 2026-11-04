package com.haishinkit.codec

import androidx.core.util.Pools
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque

internal class AudioCodecBuffer {
    var sampleRate: Int = 44100
    var channelCount: Int = 1
    var presentationTimestamp: Long = DEFAULT_PRESENTATION_TIMESTAMP
        private set
    private val pool = Pools.SynchronizedPool<ByteBuffer>(CAPACITY * 2)
    private val buffers = LinkedBlockingDeque<ByteBuffer>(CAPACITY)
    private var pending: ByteBuffer? = null
    private var anchorTimeUs = DEFAULT_PRESENTATION_TIMESTAMP
    private var samples = 0L

    fun append(byteBuffer: ByteBuffer) {
        val available = pool.acquire()
        val buffer = available?.takeIf { it.capacity() >= byteBuffer.remaining() } ?: ByteBuffer.allocateDirect(byteBuffer.remaining())
        buffer.clear()
        buffer.put(byteBuffer.duplicate())
        buffer.flip()
        if (buffers.remainingCapacity() == 0) buffers.poll()?.let { pool.release(it) }
        buffers.add(buffer)
    }

    fun render(byteBuffer: ByteBuffer): Int? {
        val buffer = pending ?: buffers.poll() ?: return null
        val count = minOf(buffer.remaining(), byteBuffer.remaining())
        val view = buffer.duplicate()
        view.limit(view.position() + count)
        byteBuffer.put(view)
        buffer.position(buffer.position() + count)
        if (buffer.hasRemaining()) {
            pending = buffer
        } else {
            pending = null
            pool.release(buffer)
        }
        if (anchorTimeUs == DEFAULT_PRESENTATION_TIMESTAMP) anchorTimeUs = System.nanoTime() / 1000
        presentationTimestamp = anchorTimeUs + samples * 1_000_000 / sampleRate
        samples += count / (2 * channelCount)
        return count
    }

    fun clear() {
        pending?.let { pool.release(it) }
        pending = null
        while (true) pool.release(buffers.poll() ?: break)
        presentationTimestamp = DEFAULT_PRESENTATION_TIMESTAMP
        anchorTimeUs = DEFAULT_PRESENTATION_TIMESTAMP
        samples = 0
    }

    companion object {
        const val CAPACITY = 4
        const val DEFAULT_PRESENTATION_TIMESTAMP = 0L
    }
}
