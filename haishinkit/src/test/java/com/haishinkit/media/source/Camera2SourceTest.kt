package com.haishinkit.media.source

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class Camera2SourceTest {
    @Test
    fun controlsAreRetainedBeforeOpeningAndIndependentPerCamera() {
        val context = RuntimeEnvironment.getApplication()
        val back = Camera2Source(context, "0")
        val front = Camera2Source(context, "1")
        back.isTorchEnabled = true
        back.zoomRatio = 2f
        assertTrue(back.isTorchEnabled)
        assertEquals(2f, back.zoomRatio)
        assertFalse(front.isTorchEnabled)
        assertEquals(1f, front.zoomRatio)
    }

    @Test
    fun invalidZoomDoesNotReplaceRequestedValue() {
        val source = Camera2Source(RuntimeEnvironment.getApplication())
        source.zoomRatio = 2f
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            val result = runCatching { source.zoomRatio = invalid }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(2f, source.zoomRatio)
        }
    }

    @Test
    fun capabilitiesUseZoomRatioRangeAndFallBackToDigitalZoom() {
        val context = RuntimeEnvironment.getApplication()
        val output = Camera2Output(context, Camera2Source(context), "0")
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadow = Shadow.extract<ShadowCameraCharacteristics>(characteristics)
        shadow.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadow.set(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 4f)
        Camera2Output::class.java.getDeclaredField("characteristics").apply { isAccessible = true }.set(output, characteristics)
        assertTrue(output.isTorchSupported)
        assertEquals(Range(1f, 4f), output.zoomRatioRange)
        shadow.set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, Range(0.5f, 8f))
        assertEquals(Range(0.5f, 8f), output.zoomRatioRange)
    }

    @Test
    @Config(sdk = [28])
    fun olderAndroidUsesDigitalZoomRange() {
        val context = RuntimeEnvironment.getApplication()
        val output = Camera2Output(context, Camera2Source(context), "0")
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(characteristics).set(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 3f)
        Camera2Output::class.java.getDeclaredField("characteristics").apply { isAccessible = true }.set(output, characteristics)
        assertEquals(Range(1f, 3f), output.zoomRatioRange)
        assertFalse(output.isTorchSupported)
    }
}
