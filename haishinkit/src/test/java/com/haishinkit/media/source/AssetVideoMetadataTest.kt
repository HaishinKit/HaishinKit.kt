package com.haishinkit.media.source

import android.media.MediaFormat
import android.util.Size
import com.haishinkit.graphics.ImageOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AssetVideoMetadataTest {
    @Test fun readsDimensionsClockRateDurationAndNormalizedRotation() {
        val format =
            MediaFormat.createVideoFormat("video/avc", 360, 240).apply {
                setInteger("rotation-degrees", 450)
                setLong(MediaFormat.KEY_DURATION, 2_000_000)
                setFloat(MediaFormat.KEY_FRAME_RATE, 29.97f)
            }
        val info = AssetVideoSource.Metadata.from(format)
        assertEquals(Size(360, 240), info.size)
        assertEquals(ImageOrientation.LEFT, info.orientation)
        assertEquals(2_000_000L, info.durationUs)
        assertEquals(33366L, info.frameDurationUs)
    }

    @Test fun displayAspectUsesSampleAspectAndVisibleCropWithoutChangingAllocationSize() {
        val format =
            MediaFormat.createVideoFormat("video/avc", 720, 480).apply {
                setInteger("sar-width", 32)
                setInteger("sar-height", 27)
            }
        val info = AssetVideoSource.Metadata.from(format)
        assertEquals(Size(720, 480), info.size)
        assertEquals(Size(853, 480), info.displaySize())
        assertEquals(Size(768, 480), info.displaySize(android.graphics.Rect(36, 0, 684, 480)))
        format.setInteger("sar-height", 0)
        assertEquals(Size(720, 480), AssetVideoSource.Metadata.from(format).displaySize())
    }

    @Test fun invalidOrMissingRateUsesThirtyFramesPerSecond() {
        val format = MediaFormat.createVideoFormat("video/avc", 360, 240)
        assertEquals(33333L, AssetVideoSource.Metadata.from(format).frameDurationUs)
        format.setFloat(MediaFormat.KEY_FRAME_RATE, Float.NaN)
        assertEquals(33333L, AssetVideoSource.Metadata.from(format).frameDurationUs)
    }
}
