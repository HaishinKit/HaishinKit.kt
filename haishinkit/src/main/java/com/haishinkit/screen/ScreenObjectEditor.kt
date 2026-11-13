package com.haishinkit.screen

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
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
    private val positionedObjects = mutableSetOf<ScreenObject>()
    private val compositionSize: Size
        get() = Size(screen.frame.width(), screen.frame.height())

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
            val composition = compositionSize
            val size = placementSize(target, composition)
            place(target, PointF(position.x - size.width / 2f, position.y - size.height / 2f), size, composition)
            positionedObjects.add(target)
        }
        objects.add(target)
    }

    /** Stops editing an object and releases cached geometry without removing it from its scene. */
    fun unregister(target: ScreenObject) {
        objects.remove(target)
        editedBounds.remove(target)
        positionedObjects.remove(target)
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

    /** Fits positioned and edited objects to the requested composition size. */
    fun fitObjectsToScreen(composition: Size) {
        if (composition.width <= 0 || composition.height <= 0) return
        for ((target, bounds) in editedBounds) {
            fitBounds(target, PointF(bounds.left, bounds.top), bounds.width(), bounds.height(), composition)
        }
        for (target in positionedObjects) {
            if (target in editedBounds) continue
            val origin =
                PointF(
                    target.frame.left.toFloat() + target.layoutMargin.left,
                    target.frame.top.toFloat() + target.layoutMargin.top,
                )
            val width = target.frame.width()
            val height = target.frame.height()
            if (width > 0 && height > 0 && (width > composition.width || height > composition.height)) {
                fitBounds(target, origin, width.toFloat(), height.toFloat(), composition)
            } else {
                place(target, origin, placementSize(target, composition), composition)
            }
        }
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

    private fun placementSize(
        target: ScreenObject,
        composition: Size,
    ): Size {
        val width = target.frame.width()
        val height = target.frame.height()
        return if (target is TextScreenObject) {
            target.measureText(if (width > 0) width else composition.width + width, if (height > 0) height else composition.height + height)
        } else {
            Size(if (width > 0) width else target.bounds.width().coerceAtLeast(0), if (height > 0) height else target.bounds.height().coerceAtLeast(0))
        }
    }

    private fun place(
        target: ScreenObject,
        origin: PointF,
        size: Size,
        composition: Size,
    ) {
        val left = origin.x.coerceIn(0f, max(0, composition.width - size.width).toFloat())
        val top = origin.y.coerceIn(0f, max(0, composition.height - size.height).toFloat())
        target.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_LEFT
        target.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_TOP
        target.layoutMargin.set(top.roundToInt(), left.roundToInt(), 0, 0)
        target.frame = Rect(0, 0, target.frame.width(), target.frame.height())
        target.invalidateLayout()
    }

    private fun fitBounds(
        target: ScreenObject,
        origin: PointF,
        width: Float,
        height: Float,
        composition: Size,
    ) {
        val scale = min(1f, min(composition.width / width, composition.height / height))
        if (target is TextScreenObject && scale != 1f) target.size *= scale
        applyBounds(target, origin, width * scale, height * scale, composition)
    }

    private fun applyBounds(
        target: ScreenObject,
        origin: PointF,
        width: Float,
        height: Float,
        composition: Size = compositionSize,
    ) {
        val frameWidth = max(width, 1f)
        val frameHeight = max(height, 1f)
        val left = origin.x.coerceIn(0f, max(0f, composition.width - frameWidth))
        val top = origin.y.coerceIn(0f, max(0f, composition.height - frameHeight))
        positionedObjects.add(target)
        editedBounds[target] = RectF(left, top, left + frameWidth, top + frameHeight)
        target.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_LEFT
        target.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_TOP
        target.layoutMargin.set(0, 0, 0, 0)
        val roundedWidth = frameWidth.roundToInt()
        val roundedHeight = frameHeight.roundToInt()
        val roundedLeft = min(left.roundToInt(), max(0, composition.width - roundedWidth))
        val roundedTop = min(top.roundToInt(), max(0, composition.height - roundedHeight))
        target.frame = Rect(roundedLeft, roundedTop, roundedLeft + roundedWidth, roundedTop + roundedHeight)
        target.invalidateLayout()
    }
}
