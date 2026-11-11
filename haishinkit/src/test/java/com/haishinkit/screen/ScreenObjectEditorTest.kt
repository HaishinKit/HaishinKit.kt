package com.haishinkit.screen

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScreenObjectEditorTest {
    @Test
    fun hitTestingUsesDrawingOrderVisibilityAndCompositionBounds() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val back = ScreenObjectContainer().apply { bounds.set(100, 100, 300, 300) }
        val front = ScreenObjectContainer().apply { bounds.set(back.bounds) }
        val editor = ScreenObjectEditor(screen, listOf(back, front))
        assertEquals(front, editor.hitTest(PointF(150f, 150f)))
        front.isVisible = false
        assertEquals(back, editor.hitTest(PointF(150f, 150f)))
        back.bounds.set(-100, -100, 200, 200)
        assertNull(editor.hitTest(PointF(-10f, -10f)))
        assertNull(editor.hitTest(PointF(Float.NaN, 0f)))
        assertNull(editor.hitTest(PointF(1000f, 1000f)))
    }

    @Test
    fun proportionalTransformPreservesAnchorWhilePanningAndClamps() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = ScreenObjectContainer().apply { bounds.set(200, 200, 400, 400) }
        val editor = ScreenObjectEditor(screen, listOf(target))
        assertTrue(editor.transform(target, PointF(300f, 300f), PointF(350f, 400f), 2f))
        assertEquals(Rect(150, 200, 550, 600), target.frame)
        assertTrue(editor.moveTo(target, PointF(900f, -100f)))
        assertEquals(Rect(600, 0, 1000, 400), target.frame)
        assertTrue(target.shouldInvalidateLayout)
        assertEquals(ScreenObject.HORIZONTAL_ALIGNMENT_LEFT, target.horizontalAlignment)
    }

    @Test
    fun translationPreservesNarrowTextSize() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 200, 200) }
        val text = TextScreenObject().apply { bounds.set(20, 20, 30, 70) }
        val fontSize = text.size
        val editor = ScreenObjectEditor(screen, listOf(text))

        assertTrue(editor.transform(text, PointF(20f, 20f), PointF(40f, 50f)))

        assertEquals(Rect(40, 50, 50, 100), text.frame)
        assertEquals(fontSize, text.size, 0f)
    }

    @Test
    fun movingToOriginInvalidatesLayoutWhenOnlyAlignmentChanges() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 200, 200) }
        val target =
            ScreenObjectContainer().apply {
                frame = Rect(0, 0, 40, 50)
                horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
                verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM
            }
        val renderer = MockRenderer()
        screen.addChild(target)
        screen.layout(renderer)
        assertEquals(Rect(160, 150, 200, 200), target.bounds)
        val editor = ScreenObjectEditor(screen, listOf(target))

        assertTrue(editor.moveTo(target, PointF()))

        assertEquals(Rect(0, 0, 40, 50), target.frame)
        screen.layout(renderer)
        assertEquals(Rect(0, 0, 40, 50), target.bounds)
    }

    @Test
    fun registrationPreservesSignedAutomaticDimensions() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 200, 200) }
        val target = ScreenObjectContainer().apply { frame = Rect(10, 20, -30, 70) }
        val editor = ScreenObjectEditor(screen)

        editor.register(target, PointF(50f, 50f))

        assertEquals(Rect(0, 0, -40, 50), target.frame)
    }

    @Test
    fun repeatedTransformsKeepFractionalGeometryBeforeLayout() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = ScreenObjectContainer().apply { bounds.set(200, 200, 400, 400) }
        val editor = ScreenObjectEditor(screen, listOf(target))
        repeat(10) {
            val bounds = editor.getBounds(target)!!
            editor.moveTo(target, PointF(bounds.left + 0.2f, bounds.top + 0.2f))
        }
        assertEquals(202f, editor.getBounds(target)!!.left, 0.001f)
        assertEquals(Rect(202, 202, 402, 402), target.frame)
        editor.transform(target, PointF(250f, 250f), scale = 2f)
        editor.transform(target, PointF(250f, 250f), scale = 2f)
        assertEquals(800f, editor.getBounds(target)!!.width(), 0.001f)
    }

    @Test
    fun sizeLimitsAreConfigurableAndTextFontScales() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 500) }
        val target =
            TextScreenObject().apply {
                size = 60f
                bounds.set(200, 100, 400, 200)
            }
        val editor = ScreenObjectEditor(screen, listOf(target), minimumSize = 40f)
        editor.transform(target, PointF(250f, 150f), scale = 2f)
        assertEquals(120f, target.size, 0.001f)
        editor.transform(target, PointF(250f, 150f), scale = 0.001f)
        assertEquals(40, target.frame.height())
        assertEquals(80, target.frame.width())
        editor.transform(target, PointF(250f, 150f), scale = 100f)
        assertEquals(Rect(0, 0, 1000, 500), target.frame)
    }

    @Test
    fun registrationDoesNotOwnSceneAndUnregistrationDropsCachedGeometry() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = ScreenObjectContainer().apply { frame = Rect(0, 0, 200, 200) }
        val editor = ScreenObjectEditor(screen)
        editor.register(target, PointF(980f, 10f))
        assertNull(target.parent)
        assertEquals(800, target.layoutMargin.left)
        assertEquals(0, target.layoutMargin.top)
        target.bounds.set(800, 0, 1000, 200)
        editor.register(target, PointF(0f, 0f))
        assertEquals(800, target.layoutMargin.left)
        editor.moveTo(target, PointF(500f, 500f))
        editor.unregister(target)
        assertFalse(editor.contains(target))
        assertNull(editor.getBounds(target))
        assertNull(editor.hitTest(PointF(550f, 550f)))
        assertFalse(editor.moveTo(target, PointF()))
        assertFalse(editor.transform(target, PointF(), scale = 2f))
    }

    @Test
    fun externalLayoutCanInvalidateCacheAndReturnedBoundsAreCopies() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = ScreenObjectContainer().apply { bounds.set(100, 100, 300, 300) }
        val editor = ScreenObjectEditor(screen, listOf(target))
        editor.moveTo(target, PointF(200f, 200f))
        editor.getBounds(target)!!.setEmpty()
        assertEquals(RectF(200f, 200f, 400f, 400f), editor.getBounds(target))
        target.bounds.set(300, 300, 600, 500)
        editor.invalidateBounds(target)
        assertEquals(RectF(300f, 300f, 600f, 500f), editor.getBounds(target))
    }

    @Test
    fun invalidTransformsLeaveFrameAndFontUnchanged() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = TextScreenObject().apply { bounds.set(100, 100, 300, 200) }
        val editor = ScreenObjectEditor(screen, listOf(target))
        val fontSize = target.size
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(editor.transform(target, PointF(150f, 150f), scale = scale))
        }
        assertFalse(editor.transform(target, PointF(Float.NaN, 0f)))
        assertFalse(editor.moveTo(target, PointF(Float.POSITIVE_INFINITY, 0f)))
        assertEquals(Rect(), target.frame)
        assertEquals(fontSize, target.size, 0f)
    }
}
