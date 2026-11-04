package com.haishinkit.media

/** Keeps queued output access and release inside its decoder's lifetime lock. */
internal class CodecOutputBuffer(
    private val guard: Any,
    private val isCurrent: () -> Boolean,
    private val release: (Boolean, Long) -> Unit,
) {
    private var released = false

    fun process(block: () -> Unit): Boolean =
        synchronized(guard) {
            if (released || !isCurrent()) {
                false
            } else {
                block()
                true
            }
        }

    fun release(
        render: Boolean,
        timestamp: Long,
    ) {
        synchronized(guard) {
            if (released) return
            released = true
            if (isCurrent()) release.invoke(render, timestamp)
        }
    }
}
