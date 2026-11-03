package com.haishinkit.media

/** Continuous playback time across AudioTrack replacement and clock fallback. */
internal class MediaPlaybackClock {
    var timeUs = 0L
        private set
    private var audioOffsetUs = 0L
    private var clockOffsetUs = 0L

    fun replaceAudio(previousHeadUs: Long?) {
        if (previousHeadUs != null) audioTime(previousHeadUs)
        audioOffsetUs = timeUs
    }

    fun useClock() {
        clockOffsetUs = timeUs
    }

    fun audioTime(headUs: Long): Long {
        timeUs = maxOf(timeUs, audioOffsetUs + headUs)
        return timeUs
    }

    fun clockTime(elapsedUs: Long): Long {
        timeUs = maxOf(timeUs, clockOffsetUs + elapsedUs)
        return timeUs
    }

    fun reset() {
        timeUs = 0
        audioOffsetUs = 0
        clockOffsetUs = 0
    }
}
