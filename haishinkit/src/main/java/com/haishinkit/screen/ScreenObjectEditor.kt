package com.haishinkit.screen

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hit testing, positioning and proportional resizing for editable direct children of a composition.
 *
 * [screen] supplies the composition size through its frame. Supply [objects] in drawing order
 * (last drawn is frontmost); exclude background video or other objects that should not be edited.
 * Register/unregister objects alongside scene insertion/removal. This editor does not attach,
 * detach, reorder, or own their rendering resources, and does not handle gestures or selection UI.
 *
 * All input coordinates are composition pixels. Call from one thread, as with direct frame
 * changes. Bounds are taken from the most recent layout until edited, then retained at fractional
 * precision so consecutive input events do not depend on rendering completing between events.
 * Call [invalidateBounds] after changing an object's layout outside this editor.
 */
class ScreenObjectEditor(
    private val screen: ScreenObject,
    objects: List<ScreenObject> = emptyList(),
    val minimumSize: Float = 24f,
) {
    private val objects = objects.distinct().toMutableList()
    private val editedBounds = mutableMapOf<ScreenObject, RectF>()

    init {
        require(minimumSize.isFinite() && minimumSize > 0f) { "minimumSize must be finite and positive" }
    }

    /** Whether an object is registered for editing. */
    fun contains(target: ScreenObject): Boolean = target in objects

    /**
     * Registers an object at the front of the editable drawing order. Optionally centers its
     * requested size at [position], clamping its position inside the composition. Auto-sized dimensions
     * retain their automatic layout behavior. Duplicate registrations are ignored.
     */
    fun register(
        target: ScreenObject,
        position: PointF? = null,
    ) {
        if (contains(target)) return
        if (position != null) {
            require(position.x.isFinite() && position.y.isFinite()) { "position must be finite" }
            val width = target.frame.width().coerceAtLeast(0)
            val height = target.frame.height().coerceAtLeast(0)
            val left = (position.x - width / 2f).coerceIn(0f, max(0f, screen.frame.width().toFloat() - width))
            val top = (position.y - height / 2f).coerceIn(0f, max(0f, screen.frame.height().toFloat() - height))
            target.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_LEFT
            target.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_TOP
            target.layoutMargin.set(top.roundToInt(), left.roundToInt(), 0, 0)
            target.frame = Rect(0, 0, target.frame.width(), target.frame.height())
            target.invalidateLayout()
        }
        objects.add(target)
    }

    /** Stops editing an object and releases cached geometry without removing it from its scene. */
    fun unregister(target: ScreenObject) {
        objects.remove(target)
        editedBounds.remove(target)
    }

    /** Returns the frontmost visible registered object at [point], or `null` outside the composition. */
    fun hitTest(point: PointF): ScreenObject? {
        if (!point.x.isFinite() || !point.y.isFinite() ||
            point.x < 0 || point.y < 0 || point.x >= screen.frame.width() || point.y >= screen.frame.height()
        ) {
            return null
        }
        return objects.asReversed().firstOrNull {
            it.isVisible && boundsOf(it).contains(point.x, point.y)
        }
    }

    /** Returns a copy of the current editing bounds, or `null` for an unregistered object. */
    fun getBounds(target: ScreenObject): RectF? = if (contains(target)) RectF(boundsOf(target)) else null

    /** Discards cached geometry so subsequent operations use the object's next rendered bounds. */
    fun invalidateBounds(target: ScreenObject) {
        editedBounds.remove(target)
    }

    /** Moves the object's top-left corner, preserving its current size and clamping its position. */
    fun moveTo(
        target: ScreenObject,
        position: PointF,
    ): Boolean {
        val bounds = getBounds(target) ?: return false
        if (!position.x.isFinite() || !position.y.isFinite() || bounds.isEmpty || screen.frame.width() <= 0 || screen.frame.height() <= 0) return false
        applyBounds(target, position, bounds.width(), bounds.height())
        return true
    }

    /**
     * Scales around [anchor] while moving that anchor to [destination]. Use equal points to zoom
     * in place, or a scale of 1 to pan. The aspect ratio is preserved; the smaller dimension is
     * limited by [minimumSize] and the larger size by the composition. Text font size scales too.
     * Alignment and margins are resolved into a left/top frame. Invalid input returns `false`.
     */
    fun transform(
        target: ScreenObject,
        anchor: PointF,
        destination: PointF = anchor,
        scale: Float = 1f,
    ): Boolean {
        if (!scale.isFinite() || scale <= 0f ||
            !anchor.x.isFinite() || !anchor.y.isFinite() || !destination.x.isFinite() || !destination.y.isFinite()
        ) {
            return false
        }
        val bounds = getBounds(target) ?: return false
        if (bounds.isEmpty || screen.frame.width() <= 0 || screen.frame.height() <= 0) return false
        val width = bounds.width()
        val height = bounds.height()
        val maximum = min(screen.frame.width() / width, screen.frame.height() / height)
        val minimum = min(max(minimumSize / width, minimumSize / height), maximum)
        val actualScale = if (scale == 1f) scale else scale.coerceIn(minimum, maximum)
        if (target is TextScreenObject && actualScale != 1f) target.size *= actualScale
        val origin =
            PointF(
                destination.x - (anchor.x - bounds.left) * actualScale,
                destination.y - (anchor.y - bounds.top) * actualScale,
            )
        applyBounds(target, origin, width * actualScale, height * actualScale)
        return true
    }

    private fun boundsOf(target: ScreenObject): RectF = editedBounds[target] ?: RectF(target.bounds)

    private fun applyBounds(
        target: ScreenObject,
        origin: PointF,
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
        target.invalidateLayout()
    }
}
