package com.haishinkit.gles

import org.junit.Assert.assertEquals
import org.junit.Test

class HlgColorTest {
    @Test fun sdrWhiteUses203NitsAndRoundTripsToHlgReferenceWhite() {
        val white = HlgColor.fromSrgb(1f, 1f, 1f)
        for (value in white) assertEquals(0.203f, value, 0.00001f)
        for (value in HlgColor.encode(white)) assertEquals(0.75f, value, 0.002f)
        for (value in HlgColor.encode(floatArrayOf(1f, 1f, 1f))) assertEquals(1f, value, 0.00001f)
    }

    @Test fun blackRemainsFiniteAndBlack() {
        for (value in HlgColor.encode(HlgColor.fromSrgb(0f, 0f, 0f))) assertEquals(0f, value, 0f)
    }

    @Test fun primariesUseTheBt2020WorkingSpace() {
        val red = HlgColor.fromSrgb(1f, 0f, 0f)
        assertEquals(0.12736f, red[0], 0.00001f)
        assertEquals(0.01403f, red[1], 0.00001f)
        assertEquals(0.00333f, red[2], 0.00001f)
    }
}
