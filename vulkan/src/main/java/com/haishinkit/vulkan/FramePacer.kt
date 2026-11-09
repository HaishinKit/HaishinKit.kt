package com.haishinkit.vulkan

/** Phase-based pacing tolerates vsync jitter without halving the requested frame rate. */
internal class FramePacer {
    private var nextFrame = 0L

    fun accept(
        timestamp: Long,
        frameRate: Int,
    ): Boolean {
        val interval = 1_000_000_000L / frameRate
        if (nextFrame == 0L || timestamp > nextFrame + interval * 2) nextFrame = timestamp
        if (timestamp + interval / 8 < nextFrame) return false
        nextFrame += interval
        return true
    }

    fun reset() {
        nextFrame = 0
    }
}
