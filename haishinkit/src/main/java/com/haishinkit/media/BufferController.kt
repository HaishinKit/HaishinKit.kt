package com.haishinkit.media

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger

internal class BufferController<T>(
    suffix: String,
) : Object() {
    interface Listener {
        fun <T> onBufferFull(controller: BufferController<T>)

        fun <T> onBufferEmpty(controller: BufferController<T>)
    }

    var listener: Listener? = null
    var bufferTime: Int = DEFAULT_BUFFER_TIME

    @Volatile
    private var waiting = true
    private var messages = LinkedBlockingDeque<T>()
    private var timestamp = AtomicInteger(0)
    private val tracker = Tracker(suffix)

    fun enqueue(
        message: T,
        timestamp: Int,
    ) {
        messages.add(message)
        this.timestamp.addAndGet(timestamp)
        start()
        tracker.doFrame(timestamp, SystemClock.uptimeMillis())
    }

    fun take(): T = messages.take()

    @Synchronized
    fun stop(condition: Boolean = true) {
        if (condition && waiting) {
            wait()
        }
    }

    fun consume(value: Int) {
        val time = this.timestamp.addAndGet(-value)
        if (time == 0) {
            waiting = true
            listener?.onBufferEmpty(this)
        }
    }

    fun clear() {
        waiting = true
        messages.clear()
        timestamp.set(0)
        tracker.clear()
    }

    @Synchronized
    private fun start() {
        if (waiting && bufferTime <= timestamp.get()) {
            waiting = false
            notifyAll()
            listener?.onBufferFull(this)
        }
    }

    private class Tracker(
        private val suffix: String,
    ) {
        private var rotated = DEFAULT_TIMESTAMP
        private var timestamps = ArrayList<Int>()

        fun doFrame(
            timestamp: Int,
            frameTimeNanos: Long,
        ) {
            if (rotated == DEFAULT_TIMESTAMP) {
                rotated = frameTimeNanos
            }
            if (1000 <= frameTimeNanos - rotated) {
                if (VERBOSE) {
                    Log.d(TAG, "$suffix: average = ${timestamps.average()}")
                }
                timestamps.clear()
                rotated += 1000
            }
            timestamps.add(timestamp)
        }

        fun clear() {
            rotated = DEFAULT_TIMESTAMP
            timestamps.clear()
        }

        companion object {
            private const val DEFAULT_TIMESTAMP = -1L
        }
    }

    companion object {
        const val DEFAULT_BUFFER_TIME = 300
        private const val VERBOSE = true
        private val TAG = BufferController::class.java.simpleName
    }
}
