package com.haishinkit.app

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.TextScreenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ln

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScreenObjectDragControllerTest {
    @Test
    fun aspectFillConvertsCroppedPreviewCoordinatesAndPreservesGrabOffset() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1280, 720) }
        val target = ScreenObjectContainer().apply { bounds.set(400, 100, 600, 300) }
        val controller = ScreenObjectDragController(screen, listOf(target))
        val preview = IntSize(720, 720)
        assertTrue(controller.begin(Offset(140f, 120f), preview))
        controller.move(Offset(190f, 160f), preview)
        assertEquals(Rect(450, 140, 650, 340), target.frame)
        controller.end()
        controller.move(Offset(300f, 300f), preview)
        assertEquals(Rect(450, 140, 650, 340), target.frame)
    }

    @Test
    fun topmostVisibleObjectMovesAndStaysInsideScreen() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 720, 1280) }
        val back = ScreenObjectContainer().apply { bounds.set(500, 1000, 700, 1200) }
        val front =
            ScreenObjectContainer().apply {
                bounds.set(back.bounds)
                horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
                verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM
                layoutMargin.set(0, 0, 20, 80)
            }
        val hidden =
            ScreenObjectContainer().apply {
                bounds.set(back.bounds)
                isVisible = false
            }
        val controller = ScreenObjectDragController(screen, listOf(back, front, hidden))
        val preview = IntSize(360, 640)
        assertTrue(controller.begin(Offset(260f, 510f), preview))
        controller.move(Offset(500f, 800f), preview)
        assertEquals(Rect(520, 1080, 720, 1280), front.frame)
        assertEquals(ScreenObject.HORIZONTAL_ALIGNMENT_LEFT, front.horizontalAlignment)
        assertEquals(ScreenObject.VERTICAL_ALIGNMENT_TOP, front.verticalAlignment)
        assertEquals(0, front.layoutMargin.right)
        assertEquals(Rect(), back.frame)
        controller.end()
        assertFalse(controller.begin(Offset.Zero, preview))
        assertFalse(controller.begin(Offset.Zero, IntSize.Zero))
    }

    @Test
    fun pinchScalesAroundCentroidWhilePanningAndThenAllowsDragging() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = ScreenObjectContainer().apply { bounds.set(200, 200, 400, 400) }
        val controller = ScreenObjectDragController(screen, listOf(target))
        val preview = IntSize(500, 500)
        assertTrue(controller.begin(Offset(125f, 125f), preview))
        controller.transform(Offset(150f, 150f), Offset(175f, 200f), 2f, preview)
        assertEquals(Rect(150, 200, 550, 600), target.frame)
        // Dropping back to one finger changes the centroid, but only its delta moves the object.
        controller.transform(Offset(200f, 200f), Offset(210f, 215f), 1f, preview)
        assertEquals(Rect(170, 230, 570, 630), target.frame)
    }

    @Test
    fun wheelScalesTopmostObjectCumulativelyBeforeRendererLayout() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val back = ScreenObjectContainer().apply { bounds.set(200, 200, 400, 400) }
        val front = ScreenObjectContainer().apply { bounds.set(back.bounds) }
        val controller = ScreenObjectDragController(screen, listOf(back, front))
        val preview = IntSize(1000, 1000)
        val delta = -ln(2f) / 0.12f
        assertTrue(controller.scroll(Offset(250f, 250f), delta, preview))
        assertEquals(Rect(150, 150, 550, 550), front.frame)
        assertTrue(controller.scroll(Offset(250f, 250f), delta, preview))
        assertEquals(Rect(50, 50, 850, 850), front.frame)
        assertEquals(Rect(), back.frame)
        assertTrue(controller.scroll(Offset(250f, 250f), -delta, preview))
        assertEquals(Rect(150, 150, 550, 550), front.frame)
    }

    @Test
    fun resizeLimitsAndInvalidInputKeepGeometryUsable() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 500) }
        val target = ScreenObjectContainer().apply { bounds.set(200, 100, 400, 200) }
        val controller = ScreenObjectDragController(screen, listOf(target))
        val preview = IntSize(1000, 500)
        assertFalse(controller.scroll(Offset.Zero, -1f, preview))
        assertFalse(controller.scroll(Offset(250f, 150f), Float.NaN, preview))
        assertTrue(controller.begin(Offset(250f, 150f), preview))
        controller.transform(Offset(250f, 150f), Offset(250f, 150f), 100f, preview)
        assertEquals(Rect(0, 0, 1000, 500), target.frame)
        controller.transform(Offset(250f, 150f), Offset(250f, 150f), 0.0001f, preview)
        assertEquals(48, target.frame.width())
        assertEquals(24, target.frame.height())
        val frame = Rect(target.frame)
        controller.transform(Offset.Zero, Offset.Zero, Float.POSITIVE_INFINITY, preview)
        assertEquals(frame, target.frame)
        assertFalse(controller.scroll(Offset(250f, 150f), -1f, preview))
    }

    @Test
    fun textFontSizeScalesWithFrame() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target =
            TextScreenObject().apply {
                size = 60f
                bounds.set(200, 200, 400, 300)
            }
        val controller = ScreenObjectDragController(screen, listOf(target))
        assertTrue(controller.scroll(Offset(250f, 250f), -ln(2f) / 0.12f, IntSize(1000, 1000)))
        assertEquals(120f, target.size, 0.001f)
        assertEquals(Rect(150, 150, 550, 350), target.frame)
    }

    @Test
    fun textEditorOnlySelectsTextWhenItIsTheTopmostHit() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val text = TextScreenObject().apply { bounds.set(100, 100, 300, 200) }
        val image = ScreenObjectContainer().apply { bounds.set(text.bounds) }
        val controller = ScreenObjectDragController(screen, listOf(text, image))
        val preview = IntSize(1000, 1000)
        assertTrue(controller.begin(Offset(150f, 150f), preview))
        assertEquals(null, controller.textForEditing())
        image.isVisible = false
        assertTrue(controller.begin(Offset(150f, 150f), preview))
        assertEquals(text, controller.textForEditing())
        controller.end()
        assertEquals(null, controller.textForEditing())
    }

    @Test
    fun editingResizedTextPreservesPositionAndFontButReleasesOldFrame() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val text =
            TextScreenObject().apply {
                value = "Before"
                size = 60f
                bounds.set(200, 200, 400, 300)
            }
        val controller = ScreenObjectDragController(screen, listOf(text))
        val preview = IntSize(1000, 1000)
        controller.scroll(Offset(250f, 250f), -ln(2f) / 0.12f, preview)
        assertTrue(controller.updateText(text, "Longer text\nSecond line"))
        assertEquals("Longer text\nSecond line", text.value)
        assertEquals(120f, text.size, 0.001f)
        assertEquals(150, text.layoutMargin.left)
        assertEquals(150, text.layoutMargin.top)
        assertEquals(Rect(), text.frame)
        // After content reflows, hit testing must use the newly rendered bounds.
        text.bounds.set(150, 150, 800, 450)
        assertTrue(controller.begin(Offset(700f, 400f), preview))
        assertEquals(text, controller.textForEditing())
    }

    @Test
    fun editingKeepsOriginalAlignmentAndRejectsBlankOrUnownedText() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val text =
            TextScreenObject().apply {
                value = "Before"
                horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
                verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM
                layoutMargin.set(0, 0, 16, 16)
            }
        val controller = ScreenObjectDragController(screen, listOf(text))
        assertFalse(controller.updateText(text, " \n"))
        assertEquals("Before", text.value)
        assertFalse(controller.updateText(TextScreenObject(), "Unowned"))
        assertTrue(controller.updateText(text, "After"))
        assertEquals("After", text.value)
        assertEquals(ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT, text.horizontalAlignment)
        assertEquals(ScreenObject.VERTICAL_ALIGNMENT_BOTTOM, text.verticalAlignment)
        assertEquals(16, text.layoutMargin.right)
    }

    @Test
    fun focusSurvivesPointerReleaseAndClearsExplicitly() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = ScreenObjectContainer().apply { bounds.set(100, 100, 300, 300) }
        val controller = ScreenObjectDragController(screen, listOf(target))
        assertTrue(controller.begin(Offset(150f, 150f), IntSize(1000, 1000)))
        controller.end()
        assertEquals(target, controller.focusedObject)
        controller.clearFocus()
        assertEquals(null, controller.focusedObject)
    }

    @Test
    fun registeredObjectsArePlacedClampedFocusedAndSelectable() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = ScreenObjectContainer().apply { frame = Rect(0, 0, 200, 200) }
        val controller = ScreenObjectDragController(screen, emptyList())
        controller.register(target, Offset(980f, 10f))
        assertEquals(target, controller.focusedObject)
        assertEquals(800, target.layoutMargin.left)
        assertEquals(0, target.layoutMargin.top)
        assertEquals(Rect(0, 0, 200, 200), target.frame)
        target.bounds.set(800, 0, 1000, 200)
        assertTrue(controller.begin(Offset(900f, 100f), IntSize(1000, 1000)))
        controller.end()
    }

    @Test
    fun unregisterRemovesFocusGestureAndHitTestGeometry() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val target = TextScreenObject().apply { bounds.set(100, 100, 300, 300) }
        val controller = ScreenObjectDragController(screen, listOf(target))
        val preview = IntSize(1000, 1000)
        controller.begin(Offset(150f, 150f), preview)
        controller.move(Offset(200f, 200f), preview)
        val frame = Rect(target.frame)
        controller.unregister(target)
        assertEquals(null, controller.focusedObject)
        assertEquals(null, controller.previewBounds(preview))
        controller.move(Offset(300f, 300f), preview)
        assertEquals(frame, target.frame)
        assertFalse(controller.begin(Offset(200f, 200f), preview))
        assertFalse(controller.scroll(Offset(200f, 200f), -1f, preview))
        assertFalse(controller.updateText(target, "Removed"))
    }

    @Test
    fun selectionFrameMatchesAspectFillCropAndTransforms() {
        val screen = ScreenObjectContainer().apply { frame = Rect(0, 0, 1280, 720) }
        val target = ScreenObjectContainer().apply { bounds.set(400, 100, 600, 300) }
        val controller = ScreenObjectDragController(screen, listOf(target))
        val preview = IntSize(720, 720)
        controller.begin(Offset(140f, 120f), preview)
        controller.end()
        assertEquals(androidx.compose.ui.geometry.Rect(120f, 100f, 320f, 300f), controller.previewBounds(preview))
        controller.scroll(Offset(220f, 200f), -ln(2f) / 0.12f, preview)
        assertEquals(androidx.compose.ui.geometry.Rect(20f, 0f, 420f, 400f), controller.previewBounds(preview))
        target.isVisible = false
        assertEquals(null, controller.previewBounds(preview))
    }
}
