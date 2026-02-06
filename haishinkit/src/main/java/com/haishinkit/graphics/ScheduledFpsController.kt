package com.haishinkit.graphics

/**
 * This source code from grafika.
 * https://github.com/google/grafika/blob/c747398a8f0d5c8ec7be2c66522a80b43dfc9a1e/app/src/main/java/com/android/grafika/ScheduledSwapActivity.java#L76
 */
internal class ScheduledFpsController : FpsController {
    override var frameRate: Int = 60
        set(value) {
            clear()
            when (true) {
                (60 <= value) -> {
                    field = 60
                    updatePatternIdx = 5
                }

                (48 <= value) -> {
                    field = 48
                    updatePatternIdx = 4
                }

                (30 <= value) -> {
                    field = 30
                    updatePatternIdx = 3
                }

                (25 <= value) -> {
                    field = 25
                    updatePatternIdx = 2
                }

                (24 <= value) -> {
                    field = 24
                    updatePatternIdx = 1
                }

                else -> {
                    field = 15
                    updatePatternIdx = 0
                }
            }
        }

    private var framesAheadIndex = DEFAULT_FRAMES_AHEAD_INDEX
    private var refreshPeriodNs = DEFAULT_REFRESH_PERIOD_NS
    private var holdFrames = DEFAULT_HOLD_FRAMES
    private var updatePatternOffset = DEFAULT_UPDATE_PATTERN_OFFSET
    private var choreographerSkips = DEFAULT_CHOREGRAPHER_SKIPS
    private var droppedFrames = DEFAULT_DROPPED_FRAMES
    private var previousRefreshNs = DEFAULT_PREVIOUS_REFRESH_NS
    private var updatePatternIdx = DEFAULT_UPDATE_PATTERN_INDEX
    private var position = DEFAULT_POSITION
    private var speed = DEFAULT_SPEED

    override fun advanced(frameTime: Long): Boolean {
        var draw = false

        if (1 < holdFrames) {
            holdFrames--
        } else {
            updatePatternOffset =
                (updatePatternOffset + 1) % UPDATE_PATTERNS[updatePatternIdx].length
            holdFrames = getHoldTime()
            draw = true
            position += speed
        }

        if (previousRefreshNs != 0L && frameTime - previousRefreshNs > refreshPeriodNs + ONE_MILLISECOND_NS) {
            choreographerSkips++
        }
        previousRefreshNs = frameTime

        val diff: Long = System.nanoTime() - frameTime
        if (diff > refreshPeriodNs - ONE_MILLISECOND_NS) {
            droppedFrames++
        }

        return draw
    }

    override fun timestamp(frameTime: Long): Long {
        val framesAhead = FRAME_AHEAD[framesAheadIndex]
        return frameTime + refreshPeriodNs * framesAhead
    }

    override fun clear() {
        framesAheadIndex = DEFAULT_FRAMES_AHEAD_INDEX
        refreshPeriodNs = DEFAULT_REFRESH_PERIOD_NS
        holdFrames = DEFAULT_HOLD_FRAMES
        updatePatternOffset = DEFAULT_UPDATE_PATTERN_OFFSET
        choreographerSkips = DEFAULT_CHOREGRAPHER_SKIPS
        droppedFrames = DEFAULT_DROPPED_FRAMES
        previousRefreshNs = DEFAULT_PREVIOUS_REFRESH_NS
        position = DEFAULT_POSITION
        speed = DEFAULT_SPEED
    }

    private fun getHoldTime(): Int {
        val ch: Char = UPDATE_PATTERNS[updatePatternIdx][updatePatternOffset]
        return ch - '0'
    }

    companion object {
        private const val DEFAULT_FRAMES_AHEAD_INDEX = 2
        private const val DEFAULT_REFRESH_PERIOD_NS = -1L
        private const val DEFAULT_HOLD_FRAMES = 0
        private const val DEFAULT_UPDATE_PATTERN_OFFSET = 0
        private const val DEFAULT_CHOREGRAPHER_SKIPS = 0
        private const val DEFAULT_DROPPED_FRAMES = 0
        private const val DEFAULT_PREVIOUS_REFRESH_NS = 0L
        private const val DEFAULT_UPDATE_PATTERN_INDEX = 5
        private const val DEFAULT_POSITION = 0
        private const val DEFAULT_SPEED = 0

        private val UPDATE_PATTERNS =
            arrayOf(
                "4", // 15 fps
                "32", // 24 fps
                "32322", // 25 fps
                "2", // 30 fps
                "2111", // 48 fps
                "1", // 60 fps
                "15", // erratic, useful for examination with systrace
            )
        private const val ONE_MILLISECOND_NS: Long = 1000000
        private val FRAME_AHEAD =
            intArrayOf(
                // sync with scheduledSwapAheadNames
                0,
                1,
                2,
                3,
            )

        @Suppress("unused")
        private val TAG = ScheduledFpsController::class.java.simpleName
    }
}
