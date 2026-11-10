package com.haishinkit.screen

import android.graphics.PointF
import android.graphics.RectF
import android.util.Size
import com.haishinkit.graphics.VideoGravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScreenCoordinateTransformTest {
    @Test
    fun aspectFillAccountsForCenteredCroppingInBothDirections() {
        val landscape = ScreenCoordinateTransform(Size(1280, 720), Size(720, 720), VideoGravity.RESIZE_ASPECT_FILL)
        assertEquals(PointF(280f, 0f), landscape.toScreen(PointF(0f, 0f)))
        assertEquals(RectF(120f, 100f, 320f, 300f), landscape.toPreview(RectF(400f, 100f, 600f, 300f)))
        val portrait = ScreenCoordinateTransform(Size(720, 1280), Size(720, 720), VideoGravity.RESIZE_ASPECT_FILL)
        assertEquals(PointF(0f, 280f), portrait.toScreen(PointF(0f, 0f)))
    }

    @Test
    fun aspectFitMapsLetterboxBarsOutsideComposition() {
        val transform = ScreenCoordinateTransform(Size(1280, 720), Size(640, 480))
        assertEquals(PointF(0f, -120f), transform.toScreen(PointF(0f, 0f)))
        assertEquals(PointF(0f, 60f), transform.toPreview(PointF(0f, 0f)))
        assertEquals(RectF(0f, 60f, 640f, 420f), transform.toPreview(RectF(0f, 0f, 1280f, 720f)))
    }

    @Test
    fun stretchedPreviewUsesIndependentAxes() {
        val transform = ScreenCoordinateTransform(Size(1000, 500), Size(200, 300), VideoGravity.RESIZE)
        val point = transform.toScreen(PointF(100f, 150f))!!
        assertEquals(500f, point.x, 0.001f)
        assertEquals(250f, point.y, 0.001f)
        assertEquals(PointF(100f, 150f), transform.toPreview(PointF(500f, 250f)))
    }

    @Test
    fun roundTripsForEveryGravity() {
        for (gravity in VideoGravity.entries) {
            val transform = ScreenCoordinateTransform(Size(1080, 1920), Size(733, 421), gravity)
            val original = PointF(157.25f, 841.5f)
            val roundTrip = transform.toScreen(transform.toPreview(original)!!)!!
            assertEquals(original.x, roundTrip.x, 0.001f)
            assertEquals(original.y, roundTrip.y, 0.001f)
        }
    }

    @Test
    fun invalidSizesAndNonFiniteCoordinatesAreRejected() {
        val invalid = ScreenCoordinateTransform(Size(0, 720), Size(640, 480))
        assertNull(invalid.toScreen(PointF()))
        assertNull(invalid.toPreview(RectF()))
        val valid = ScreenCoordinateTransform(Size(1280, 720), Size(640, 480))
        assertNull(valid.toScreen(PointF(Float.NaN, 0f)))
        assertNull(valid.toPreview(PointF(0f, Float.POSITIVE_INFINITY)))
        assertNull(valid.toPreview(RectF(0f, 0f, Float.POSITIVE_INFINITY, 1f)))
    }
}
