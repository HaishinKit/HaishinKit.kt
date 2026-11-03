package com.haishinkit.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlaybackClockTest {
    @Test
    fun audioReconfigurationDoesNotResetElapsedVideoTime() {
        val clock = MediaPlaybackClock()
        clock.replaceAudio(null)
        assertEquals(30_000_000L, clock.audioTime(30_000_000))
        clock.replaceAudio(30_100_000)
        assertEquals(30_100_000L, clock.audioTime(0))
        assertEquals(30_120_000L, clock.audioTime(20_000))
        clock.replaceAudio(25_000)
        assertEquals(30_125_000L, clock.audioTime(0))
    }

    @Test
    fun fallbackClockAndNextTrackPreserveTheirOffsets() {
        val clock = MediaPlaybackClock()
        clock.clockTime(500_000)
        clock.replaceAudio(null)
        assertEquals(1_500_000L, clock.audioTime(1_000_000))
        clock.replaceAudio(1_050_000)
        clock.useClock()
        assertEquals(1_550_000L, clock.clockTime(0))
        assertEquals(1_650_000L, clock.clockTime(100_000))
        clock.replaceAudio(null)
        assertEquals(1_750_000L, clock.audioTime(100_000))
        clock.reset()
        assertEquals(0L, clock.clockTime(0))
    }
}
