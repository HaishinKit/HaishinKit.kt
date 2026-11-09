package com.haishinkit.codec

import android.graphics.Rect
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoCropRectTest {
    @Test
    fun decoderInclusiveCropPreservesNonzeroOriginAndBecomesExclusive() {
        val format =
            MediaFormat().apply {
                setInteger("crop-left", 16)
                setInteger("crop-top", 8)
                setInteger("crop-right", 47)
                setInteger("crop-bottom", 63)
            }
        assertEquals(Rect(16, 8, 48, 64), VideoCodec.cropRect(format))
    }

    @Test
    fun absentIncompleteOrMalformedCropFallsBackToReaderMetadata() {
        assertNull(VideoCodec.cropRect(null))
        val format = MediaFormat()
        assertNull(VideoCodec.cropRect(format))
        format.setInteger("crop-left", 16)
        assertNull(VideoCodec.cropRect(format))
        format.setInteger("crop-top", 0)
        format.setInteger("crop-right", 15)
        format.setInteger("crop-bottom", 63)
        assertNull(VideoCodec.cropRect(format))
    }
}
