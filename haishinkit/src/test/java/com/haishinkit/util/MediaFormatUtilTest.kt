package com.haishinkit.util

import android.graphics.Rect
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaFormatUtilTest {
    @Test
    fun cropPreservesNonzeroOriginAndConvertsInclusiveEdges() {
        assertEquals(Rect(16, 8, 48, 64), MediaFormatUtil.getCropRect(crop(16, 8, 47, 63)))
    }

    @Test
    fun singlePixelCropRemainsNonempty() {
        assertEquals(Rect(5, 7, 6, 8), MediaFormatUtil.getCropRect(crop(5, 7, 5, 7)))
    }

    @Test
    fun absentFormatOrCropReturnsNull() {
        assertNull(MediaFormatUtil.getCropRect(null))
        assertNull(MediaFormatUtil.getCropRect(MediaFormat()))
    }

    @Test
    fun eachMissingCropEdgeReturnsNull() {
        listOf("crop-left", "crop-top", "crop-right", "crop-bottom").forEach { missing ->
            val format = MediaFormat()
            listOf("crop-left", "crop-top", "crop-right", "crop-bottom").filter { it != missing }.forEach {
                format.setInteger(it, 0)
            }
            assertNull("Missing $missing", MediaFormatUtil.getCropRect(format))
        }
    }

    @Test
    fun negativeOriginsAndReversedEdgesReturnNull() {
        listOf(crop(-1, 0, 15, 15), crop(0, -1, 15, 15), crop(16, 0, 15, 15), crop(0, 16, 15, 15)).forEach {
            assertNull(MediaFormatUtil.getCropRect(it))
        }
    }

    @Test
    fun inclusiveEdgeOverflowReturnsNull() {
        assertNull(MediaFormatUtil.getCropRect(crop(0, 0, Int.MAX_VALUE, 15)))
        assertNull(MediaFormatUtil.getCropRect(crop(0, 0, 15, Int.MAX_VALUE)))
    }

    private fun crop(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) =
        MediaFormat().apply {
            setInteger("crop-left", left)
            setInteger("crop-top", top)
            setInteger("crop-right", right)
            setInteger("crop-bottom", bottom)
        }
}
