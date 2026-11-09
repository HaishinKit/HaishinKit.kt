package com.haishinkit.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    @Test
    fun jitterDoesNotHalveSixtyFpsOutput() {
        val pacer = FramePacer()
        val count =
            (0 until 120).count { frame ->
                pacer.accept(1_000_000_000L + frame * 16_666_666L + if (frame % 2 == 0) 100_000 else -100_000, 60)
            }
        assertEquals(120, count)
    }

    @Test
    fun thirtyFpsOnSixtyHzAndAfterLongPause() {
        val pacer = FramePacer()
        assertEquals(60, (0 until 120).count { pacer.accept(1_000_000_000L + it * 16_666_666L, 30) })
        assertTrue(pacer.accept(8_000_000_000L, 30))
        assertFalse(pacer.accept(8_000_000_001L, 30))
        pacer.reset()
        assertTrue(pacer.accept(9_000_000_000L, 30))
    }
}
