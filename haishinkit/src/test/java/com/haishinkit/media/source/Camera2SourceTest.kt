package com.haishinkit.media.source

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.screen.MockRenderer
import com.haishinkit.screen.MockScreen
import com.haishinkit.screen.VideoScreenObject
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun sensor270BackAndFrontKeepTheirLandscapeOrientationWhenSwitching() {
        val context = RuntimeEnvironment.getApplication()
        val screen = MockScreen(context)
        val sources =
            listOf(CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT).map { facing ->
                val source = Camera2Source(context)
                val output = Camera2Output(context, source, "0")
                val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
                Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
                    set(CameraCharacteristics.SENSOR_ORIENTATION, 270)
                    set(CameraCharacteristics.LENS_FACING, facing)
                }
                Camera2Output::class.java.getDeclaredField("characteristics").apply { isAccessible = true }.set(output, characteristics)
                Camera2Source::class.java.getDeclaredField("output").apply { isAccessible = true }.set(source, output)
                source
            }
        val custom = object : VideoSource by sources[1] {}
        for (gravity in VideoGravity.entries) {
            for (display in listOf(Surface.ROTATION_90, Surface.ROTATION_270)) {
                val video =
                    VideoScreenObject().apply {
                        frame = Rect(0, 0, 1280, 720)
                        videoSize = Size(1920, 1080)
                        videoGravity = gravity
                        isRotatesWithContent = true
                        deviceOrientation = display
                    }
                // Back -> front -> back also exercises same-orientation source replacement.
                for (source in listOf(sources[0], sources[1], sources[0], custom)) {
                    val isBack = source === sources[0]
                    video.imageOrientation = source.imageOrientation
                    screen.updateCameraSource(video, source)
                    val expected =
                        VideoScreenObject().apply {
                            frame = video.frame
                            videoSize = video.videoSize
                            videoGravity = gravity
                            imageOrientation = if ((display == Surface.ROTATION_90) == isBack) ImageOrientation.DOWN else ImageOrientation.UP
                        }
                    video.layout(MockRenderer())
                    expected.layout(MockRenderer())
                    assertArrayEquals("back=$isBack / display=$display / $gravity", expected.matrix, video.matrix, 0.0001f)
                }
                screen.updateCameraSource(video, null)
                assertFalse(video.isBackCamera)
                screen.updateCameraSource(video, sources[1])
                assertFalse(video.isBackCamera)
            }
        }
    }
}
