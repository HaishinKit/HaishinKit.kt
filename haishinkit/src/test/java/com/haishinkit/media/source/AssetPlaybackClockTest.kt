package com.haishinkit.media.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AssetPlaybackClockTest {
    @Test fun loopEpochAndPresentationOffsetsUseTheSharedClock() {
        val clock = AssetPlaybackClock()
        clock.begin(1000, 1_000_000)
        val first = checkNotNull(clock.pass)
        assertEquals(2_000_000, first.deadline(2000))
        assertEquals(0, first.deadline(0))
        clock.begin(0, 5_000_000)
        assertNotSame(first, clock.pass)
        assertEquals(6_000_000, checkNotNull(clock.pass).deadline(1000))
    }

    @Test fun invalidPresentationTimesCannotOverflowTheClock() {
        val clock = AssetPlaybackClock()
        clock.begin(0, 1_000_000)
        assertThrows(IllegalArgumentException::class.java) { clock.pass!!.deadline(Long.MAX_VALUE) }
        clock.begin(Long.MAX_VALUE, 1_000_000)
        assertThrows(IllegalArgumentException::class.java) { clock.pass!!.deadline(Long.MIN_VALUE) }
    }
}
