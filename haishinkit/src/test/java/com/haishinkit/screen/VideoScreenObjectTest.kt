package com.haishinkit.screen

import android.graphics.Rect
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.graphics.VideoGravity
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoScreenObjectTest {
    @Test
    fun wrappedRotationPreservesAspectFitAndFillScaling() {
        for (gravity in listOf(VideoGravity.RESIZE_ASPECT, VideoGravity.RESIZE_ASPECT_FILL)) {
            val reference =
                VideoScreenObject().apply {
                    frame = Rect(0, 0, 640, 480)
                    videoSize = Size(1920, 1080)
                    videoGravity = gravity
                    imageOrientation = ImageOrientation.LEFT
                }
            val wrapped =
                VideoScreenObject().apply {
                    frame = reference.frame
                    videoSize = reference.videoSize
                    videoGravity = gravity
                    imageOrientation = ImageOrientation.RIGHT
                    isRotatesWithContent = true
                    deviceOrientation = Surface.ROTATION_180
                }
            reference.layout(MockRenderer())
            wrapped.layout(MockRenderer())
            assertArrayEquals("$gravity must render 450 degrees like 90 degrees", reference.matrix, wrapped.matrix, 0.0001f)
        }
    }
}
