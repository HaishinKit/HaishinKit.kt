package com.haishinkit.app

import android.graphics.PointF
import android.graphics.Rect
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.screen.ScreenCoordinateTransform
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectEditor
import com.haishinkit.screen.TextScreenObject
import kotlin.math.exp
import kotlin.math.roundToInt

/** Gesture and selection state for the example; geometry is handled by the core library. */
internal class ScreenObjectDragController(
    private val screen: ScreenObject,
    objects: List<ScreenObject>,
) {
    var focusedObject: ScreenObject? by mutableStateOf(null)
        private set

    private val editor = ScreenObjectEditor(screen, objects)
    private var selected: ScreenObject? = null
    private var grabOffset = Offset.Zero

    fun begin(
        position: Offset,
        previewSize: IntSize,
    ): Boolean {
        end()
        val point = screenPosition(position, previewSize) ?: return false
        val target = editor.hitTest(PointF(point.x, point.y)) ?: return false
        selected = target
        focusedObject = target
        val bounds = editor.getBounds(target) ?: return false
        grabOffset = point - Offset(bounds.left, bounds.top)
        return true
    }

    fun move(
        position: Offset,
        previewSize: IntSize,
    ) {
        val target = selected ?: return
        val point = screenPosition(position, previewSize) ?: return
        editor.moveTo(target, PointF(point.x - grabOffset.x, point.y - grabOffset.y))
    }

    fun transform(
        previous: Offset,
        current: Offset,
        zoom: Float,
        previewSize: IntSize,
    ) {
        val target = selected ?: return
        val anchor = screenPosition(previous, previewSize) ?: return
        val destination = screenPosition(current, previewSize) ?: return
        editor.transform(target, PointF(anchor.x, anchor.y), PointF(destination.x, destination.y), zoom)
    }

    fun scroll(
        position: Offset,
        delta: Float,
        previewSize: IntSize,
    ): Boolean {
        if (selected != null || !delta.isFinite() || delta == 0f) return false
        val point = coordinates(previewSize).toScreen(PointF(position.x, position.y)) ?: return false
        val target = editor.hitTest(point) ?: return false
        focusedObject = target
        return editor.transform(target, point, scale = exp((-delta * 0.12f).coerceIn(-4f, 4f)))
    }

    fun textForEditing(): TextScreenObject? = selected as? TextScreenObject

    fun updateText(
        target: TextScreenObject,
        value: String,
    ): Boolean {
        if (!editor.contains(target) || value.isBlank()) return false
        if (target.value == value) return true
        // Release a fixed editing frame so longer text and explicit newlines can reflow.
        if (target.frame.width() > 0 || target.frame.height() > 0) {
            editor.getBounds(target)?.let { bounds ->
                target.layoutMargin.set(bounds.top.roundToInt(), bounds.left.roundToInt(), 0, 0)
                target.frame = Rect()
            }
        }
        editor.invalidateBounds(target)
        target.value = value
        return true
    }

    fun clearFocus() {
        end()
        focusedObject = null
    }

    fun register(
        target: ScreenObject,
        position: Offset,
    ) {
        if (editor.contains(target)) return
        editor.register(target, PointF(position.x, position.y))
        focusedObject = target
    }

    fun unregister(target: ScreenObject) {
        editor.unregister(target)
        if (selected == target) end()
        if (focusedObject == target) focusedObject = null
    }

    fun previewBounds(previewSize: IntSize): androidx.compose.ui.geometry.Rect? {
        val target = focusedObject ?: return null
        if (!target.isVisible) return null
        val bounds = editor.getBounds(target)?.takeUnless { it.isEmpty } ?: return null
        val rect = coordinates(previewSize).toPreview(bounds) ?: return null
        return androidx.compose.ui.geometry.Rect(rect.left, rect.top, rect.right, rect.bottom)
    }

    fun end() {
        selected = null
    }

    fun fitObjectsToScreen(composition: Size) {
        end()
        editor.fitObjectsToScreen(composition)
    }

    fun screenPosition(
        position: Offset,
        previewSize: IntSize,
    ): Offset? {
        val point = coordinates(previewSize).toScreen(PointF(position.x, position.y)) ?: return null
        return Offset(point.x, point.y)
    }

    private fun coordinates(previewSize: IntSize): ScreenCoordinateTransform =
        ScreenCoordinateTransform(
            Size(screen.frame.width(), screen.frame.height()),
            Size(previewSize.width, previewSize.height),
            VideoGravity.RESIZE_ASPECT_FILL,
        )
}
