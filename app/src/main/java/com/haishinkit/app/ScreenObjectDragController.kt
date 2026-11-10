package com.haishinkit.app

import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.TextScreenObject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Touch and mouse editing for the example's direct children in an aspect-fill preview. */
internal class ScreenObjectDragController(
    private val screen: ScreenObject,
    objects: List<ScreenObject>,
) {
    var focusedObject: ScreenObject? by mutableStateOf(null)
        private set

    private val objects = objects.toMutableList()
    private var selected: ScreenObject? = null
    private var grabOffset = Offset.Zero

    // Keep fractional geometry between input events, even before the renderer completes layout.
    private val editedBounds = mutableMapOf<ScreenObject, RectF>()

    fun begin(
        position: Offset,
        previewSize: IntSize,
    ): Boolean {
        end()
        val point = screenPosition(position, previewSize) ?: return false
        val target = hitTest(point) ?: return false
        selected = target
        focusedObject = target
        val bounds = boundsOf(target)
        grabOffset = point - Offset(bounds.left, bounds.top)
        return true
    }

    fun move(
        position: Offset,
        previewSize: IntSize,
    ) {
        val target = selected ?: return
        val point = screenPosition(position, previewSize) ?: return
        val bounds = boundsOf(target)
        applyBounds(target, point - grabOffset, bounds.width(), bounds.height())
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
        resize(target, anchor, destination, zoom)
    }

    fun scroll(
        position: Offset,
        delta: Float,
        previewSize: IntSize,
    ): Boolean {
        if (selected != null || !delta.isFinite() || delta == 0f) return false
        val point = screenPosition(position, previewSize) ?: return false
        val target = hitTest(point) ?: return false
        focusedObject = target
        // Positive wheel deltas scroll down (shrink); scrolling up enlarges the object.
        resize(target, point, point, exp((-delta * 0.12f).coerceIn(-4f, 4f)))
        return true
    }

    fun textForEditing(): TextScreenObject? = selected as? TextScreenObject

    fun updateText(
        target: TextScreenObject,
        value: String,
    ): Boolean {
        if (target !in objects || value.isBlank()) return false
        if (target.value == value) return true
        // Let text measure its new content instead of clipping it to its previous drag/zoom frame.
        editedBounds.remove(target)?.let { bounds ->
            target.layoutMargin.set(bounds.top.roundToInt(), bounds.left.roundToInt(), 0, 0)
            target.frame = Rect()
        }
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
        if (target in objects) return
        val width = target.frame.width().coerceAtLeast(0)
        val height = target.frame.height().coerceAtLeast(0)
        val left = (position.x - width / 2f).coerceIn(0f, max(0f, screen.frame.width().toFloat() - width))
        val top = (position.y - height / 2f).coerceIn(0f, max(0f, screen.frame.height().toFloat() - height))
        target.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_LEFT
        target.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_TOP
        target.layoutMargin.set(top.roundToInt(), left.roundToInt(), 0, 0)
        target.frame = Rect(0, 0, width, height)
        target.invalidateLayout()
        objects.add(target)
        focusedObject = target
    }

    fun unregister(target: ScreenObject) {
        objects.remove(target)
        editedBounds.remove(target)
        if (selected == target) end()
        if (focusedObject == target) focusedObject = null
    }

    fun previewBounds(previewSize: IntSize): androidx.compose.ui.geometry.Rect? {
        val target = focusedObject ?: return null
        if (!target.isVisible || previewSize.width <= 0 || previewSize.height <= 0) return null
        val width = screen.frame.width()
        val height = screen.frame.height()
        if (width <= 0 || height <= 0) return null
        val bounds = boundsOf(target)
        if (bounds.isEmpty) return null
        val scale = max(previewSize.width.toFloat() / width, previewSize.height.toFloat() / height)
        val cropX = (width * scale - previewSize.width) / 2f
        val cropY = (height * scale - previewSize.height) / 2f
        return androidx.compose.ui.geometry.Rect(
            bounds.left * scale - cropX,
            bounds.top * scale - cropY,
            bounds.right * scale - cropX,
            bounds.bottom * scale - cropY,
        )
    }

    fun end() {
        selected = null
    }

    fun screenPosition(
        position: Offset,
        previewSize: IntSize,
    ): Offset? {
        val width = screen.frame.width()
        val height = screen.frame.height()
        if (!position.x.isFinite() || !position.y.isFinite()) return null
        if (width <= 0 || height <= 0 || previewSize.width <= 0 || previewSize.height <= 0) return null
        val scale = max(previewSize.width.toFloat() / width, previewSize.height.toFloat() / height)
        val cropX = (width * scale - previewSize.width) / 2f
        val cropY = (height * scale - previewSize.height) / 2f
        return Offset((position.x + cropX) / scale, (position.y + cropY) / scale)
    }

    private fun hitTest(point: Offset): ScreenObject? =
        objects.asReversed().firstOrNull {
            it.isVisible && boundsOf(it).contains(point.x, point.y)
        }

    private fun boundsOf(target: ScreenObject): RectF = editedBounds[target] ?: RectF(target.bounds)

    private fun resize(
        target: ScreenObject,
        anchor: Offset,
        destination: Offset,
        zoom: Float,
    ) {
        if (!zoom.isFinite() || zoom <= 0f) return
        val bounds = boundsOf(target)
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0f || height <= 0f) return
        val maximum = min(screen.frame.width() / width, screen.frame.height() / height)
        val minimum = min(max(24f / width, 24f / height), maximum)
        val scale = zoom.coerceIn(minimum, maximum)
        if (target is TextScreenObject && scale != 1f) {
            target.size *= scale
        }
        val origin =
            Offset(
                destination.x - (anchor.x - bounds.left) * scale,
                destination.y - (anchor.y - bounds.top) * scale,
            )
        applyBounds(target, origin, width * scale, height * scale)
    }

    private fun applyBounds(
        target: ScreenObject,
        origin: Offset,
        width: Float,
        height: Float,
    ) {
        val left = origin.x.coerceIn(0f, max(0f, screen.frame.width().toFloat() - width))
        val top = origin.y.coerceIn(0f, max(0f, screen.frame.height().toFloat() - height))
        editedBounds[target] = RectF(left, top, left + width, top + height)
        target.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_LEFT
        target.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_TOP
        target.layoutMargin.set(0, 0, 0, 0)
        target.frame = Rect(left.roundToInt(), top.roundToInt(), (left + width).roundToInt(), (top + height).roundToInt())
    }
}
