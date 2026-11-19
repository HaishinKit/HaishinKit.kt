package com.haishinkit.app

import android.util.Size
import com.haishinkit.media.source.Camera2Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CameraSourceReplacementTest {
    @Test
    fun overlayAdditionAndRemovalKeepRequestedControls() {
        val context = RuntimeEnvironment.getApplication()
        val original =
            Camera2Source(context, "0").apply {
                isTorchEnabled = true
                zoomRatio = 2.5f
            }
        val withOverlay = original.replacement(context, "0", Size(1280, 720))
        assertEquals(Size(1280, 720), withOverlay.maximumSize)
        val withoutOverlay = withOverlay.replacement(context, "0")
        assertEquals(null, withoutOverlay.maximumSize)
        for (source in listOf(withOverlay, withoutOverlay)) {
            assertTrue(source.isTorchEnabled)
            assertEquals(2.5f, source.zoomRatio)
        }
    }

    @Test
    fun anotherCameraDoesNotInheritControls() {
        val context = RuntimeEnvironment.getApplication()
        val original =
            Camera2Source(context, "0").apply {
                isTorchEnabled = true
                zoomRatio = 2.5f
            }
        val front = original.replacement(context, "1")
        assertFalse(front.isTorchEnabled)
        assertEquals(1f, front.zoomRatio)
    }
}
