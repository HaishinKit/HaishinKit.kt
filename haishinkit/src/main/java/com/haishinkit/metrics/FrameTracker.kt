package com.haishinkit.metrics

import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt

internal class FrameTracker {
    private class Frame(
        val type: String,
    ) {
        private var count = 0
        private var rotated = DEFAULT_TIMESTAMP
        private var timestamp = DEFAULT_TIMESTAMP
        private var timestamps = ArrayList<Long>()

        fun doFrame(frameTimeNanos: Long) {
            val diff: Long
            count += 1
            if (timestamp == DEFAULT_TIMESTAMP) {
                diff = 0
                timestamp = frameTimeNanos
                rotated = timestamp
            } else {
                diff = frameTimeNanos - timestamp
            }
            if (1000 <= frameTimeNanos - rotated) {
                if (VERBOSE) {
                    Log.d(
                        TAG,
                        "$type stats: frames=$count, average=${average(timestamps)}, sd=${
                            sd(timestamps)
                        }",
                    )
                }
                timestamps.clear()
                rotated += 1000
                count = 0
            }
            timestamps.add(diff)
            timestamp = frameTimeNanos
        }

        fun clear() {
            count = 0
            rotated = DEFAULT_TIMESTAMP
            timestamp = DEFAULT_TIMESTAMP
            timestamps.clear()
        }

        private fun average(timestamps: List<Long>): Double = timestamps.average()

        private fun sd(timestamps: List<Long>): Double {
            val mean = average(timestamps)
            return sqrt(
                timestamps.fold(
                    0.0,
                    { accumulator, next -> accumulator + (next - mean).pow(2.0) },
                ) / timestamps.size,
            )
        }
    }

    private var audio = Frame("audio")
    private var video = Frame("video")

    fun track(
        type: Int,
        timestamp: Long,
    ) {
        when (type) {
            TYPE_AUDIO -> {
                audio.doFrame(timestamp)
            }

            TYPE_VIDEO -> {
                video.doFrame(timestamp)
            }
        }
    }

    fun clear() {
        audio.clear()
        video.clear()
    }

    companion object {
        const val TYPE_AUDIO = 0
        const val TYPE_VIDEO = 1

        const val VERBOSE = true
        private val TAG = FrameTracker::class.java.simpleName
        private const val DEFAULT_TIMESTAMP = -1L
    }
}
