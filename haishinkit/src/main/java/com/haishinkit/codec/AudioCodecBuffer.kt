package com.haishinkit.codec

import androidx.core.util.Pools
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque

internal class AudioCodecBuffer {
    var sampleRate: Int = 44100
    var presentationTimestamp: Long = DEFAULT_PRESENTATION_TIMESTAMP
        private set
    private var pool = Pools.SynchronizedPool<ByteBuffer>(CAPACITY * 2)
    private var buffers = LinkedBlockingDeque<ByteBuffer>(CAPACITY)

    fun append(byteBuffer: ByteBuffer) {
        val buffer = pool.acquire() ?: ByteBuffer.allocateDirect(byteBuffer.capacity())
        buffer.rewind()
        buffer.put(byteBuffer)
        if (buffers.size < CAPACITY) {
            buffers.add(buffer)
        } else {
            buffers.pop()
            buffers.put(buffer)
        }
    }

    fun render(byteBuffer: ByteBuffer): Int {
        val buffer = buffers.take()
        buffer.rewind()
        byteBuffer.put(buffer)
        pool.release(buffer)
        if (presentationTimestamp == DEFAULT_PRESENTATION_TIMESTAMP) {
            presentationTimestamp = System.nanoTime() / 1000
        } else {
            presentationTimestamp += timestamp(byteBuffer.capacity() / 2)
        }
        return byteBuffer.capacity()
    }

    fun clear() {
        buffers.clear()
        presentationTimestamp = DEFAULT_PRESENTATION_TIMESTAMP
    }

    private fun timestamp(sampleCount: Int): Long = ((sampleCount.toFloat() / sampleRate.toFloat())).toLong()

    companion object {
        const val CAPACITY = 4
        const val DEFAULT_PRESENTATION_TIMESTAMP = 0L
    }
}
