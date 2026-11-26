package com.haishinkit.screen

import android.graphics.Rect
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.graphics.VideoGravity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VideoScreenObjectTest {
    @Test
    fun sensor270RotationRemainsConsistentInBothLandscapeDirections() {
        val cases =
            listOf(
                Triple(ImageOrientation.RIGHT, Surface.ROTATION_90, ImageOrientation.DOWN),
                Triple(ImageOrientation.RIGHT, Surface.ROTATION_270, ImageOrientation.UP),
                Triple(ImageOrientation.RIGHT_MIRRORED, Surface.ROTATION_90, ImageOrientation.UP_MIRRORED),
                Triple(ImageOrientation.RIGHT_MIRRORED, Surface.ROTATION_270, ImageOrientation.DOWN_MIRRORED),
            )
        for (gravity in VideoGravity.entries) {
            for ((source, display, expected) in cases) {
                val camera =
                    VideoScreenObject().apply {
                        frame = Rect(0, 0, 1280, 720)
                        videoSize = Size(1920, 1080)
                        videoGravity = gravity
                        imageOrientation = source
                        isBackCamera = true
                        isRotatesWithContent = true
                        deviceOrientation = display
                    }
                val upright =
                    VideoScreenObject().apply {
                        frame = camera.frame
                        videoSize = camera.videoSize
                        videoGravity = gravity
                        imageOrientation = expected
                    }
                camera.layout(MockRenderer())
                upright.layout(MockRenderer())
                assertArrayEquals("$source / display=$display / $gravity", upright.matrix, camera.matrix, 0.0001f)
            }
        }
    }

    @Test
    fun removingCameraObjectClearsSourceMetadataBeforeReuse() {
        val screen = com.haishinkit.gles.screen.Screen(RuntimeEnvironment.getApplication())
        val video =
            VideoScreenObject().apply {
                frame = Rect(0, 0, 1280, 720)
                videoSize = Size(1920, 1080)
                imageOrientation = ImageOrientation.RIGHT
                isRotatesWithContent = true
                deviceOrientation = Surface.ROTATION_270
            }
        screen.addChild(video)
        // Simulate metadata supplied by a bound back-camera track.
        video.isBackCamera = true
        video.layout(MockRenderer())
        assertTrue(video.isBackCamera)
        screen.removeChild(video)
        assertFalse(video.isBackCamera)
        assertTrue(video.shouldInvalidateLayout)
        val nonCamera =
            VideoScreenObject().apply {
                frame = video.frame
                videoSize = video.videoSize
                imageOrientation = video.imageOrientation
                isRotatesWithContent = true
                deviceOrientation = video.deviceOrientation
            }
        video.layout(MockRenderer())
        nonCamera.layout(MockRenderer())
        assertArrayEquals(nonCamera.matrix, video.matrix, 0.0001f)
    }

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
