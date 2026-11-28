package com.haishinkit.screen

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.opengl.GLES20
import android.util.Log
import androidx.annotation.RestrictTo
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import java.util.UUID
import kotlin.math.max

/**
 * Base class for objects rendered in a [Screen].
 */
@Suppress("MemberVisibilityCanBePrivate")
abstract class ScreenObject(
    id: String?,
    val target: Int = GLES20.GL_TEXTURE_2D,
) {
    /**
     * Logical type of this screen object.
     *
     * This value is typically used for serialization, debugging,
     * or distinguishing between different kinds of screen objects.
     */
    abstract val type: String

    /**
     * Unique identifier of this screen object.
     *
     * The identifier must be unique within the owning scene or document
     * and is commonly used for lookup and state management.
     */
    val id: String = id ?: UUID.randomUUID().toString()

    /**
     * OpenGL ES texture ID associated with this screen object.
     *
     * A value of `-1` indicates that no texture has been assigned yet.
     *
     * The setter is restricted to internal use to prevent external
     * modification of the OpenGL resource state.
     */
    open var textureId: Int = -1
        internal set

    /**
     * The container that owns this object, or `null` when detached.
     */
    open var parent: ScreenObjectContainer? = null
        internal set(value) {
            (root as? Screen)?.unbind(this)
            field = value
            (root as? Screen)?.bind(this)
        }

    /**
     * The requested layout rectangle, in pixels.
     *
     * For children, a non-positive width or height uses the available parent dimension minus
     * margins, adjusted by that value. Assign a new rectangle or call [invalidateLayout] after
     * mutating the existing rectangle.
     */
    open var frame = Rect(0, 0, 0, 0)
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * The rectangle computed by the most recent layout pass, in pixels.
     */
    val bounds = Rect(0, 0, 0, 0)

    /**
     * Margins used to position and size this object, in pixels.
     */
    val layoutMargin: EdgeInsets = EdgeInsets(0, 0, 0, 0)

    /**
     * The 4 × 4 model-view-projection matrix used for rendering.
     */
    val matrix =
        FloatArray(16).apply {
            this[0] = 1f
            this[5] = 1f
            this[10] = 1f
            this[15] = 1f
        }

    /**
     * Specifies the alignment position along the horizontal axis.
     */
    var horizontalAlignment: Int = HORIZONTAL_ALIGNMENT_LEFT

    /**
     * Specifies the alignment position along the vertical axis.
     */
    var verticalAlignment: Int = VERTICAL_ALIGNMENT_TOP

    /**
     * The radius of the object's rounded corners, in composition pixels. Zero keeps square
     * corners. The effective radius is limited to half of the object's shorter side.
     */
    var cornerRadius: Float = 0f
        set(value) {
            field = if (value.isFinite() && value > 0f) value else 0f
        }

    /**
     * The effect applied when rendering this object.
     */
    var videoEffect: VideoEffect = DefaultVideoEffect.shared

    /**
     * A key-value representation of this object for serialization.
     *
     * This property is a computed view of the internal state and does not
     * necessarily have a backing field.
     */
    abstract var elements: Map<String, String>

    /**
     * Specifies the visibility of the object.
     */
    open var isVisible = true

    /**
     * Whether the object needs another layout pass.
     */
    open var shouldInvalidateLayout = false
        protected set

    internal val root: ScreenObject?
        get() {
            var parent: ScreenObject? = this.parent
            while (parent?.parent != null) {
                parent = parent.parent
            }
            return parent
        }

    private var cornerClip: CornerClip? = null

    /** Returns packed scissor rectangles in local coordinates for the renderers. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun getCornerClip(): IntArray? {
        val width = bounds.width()
        val height = bounds.height()
        val radius = cornerRadius.coerceAtMost(minOf(width, height) / 2f)
        if (radius <= 0f) return null
        cornerClip?.let {
            if (it.width == width && it.height == height && it.radius == radius) return it.rectangles
        }

        // A Region decomposes the rounded outline into non-overlapping scanline bands.
        val path = Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW) }
        val clip = Region(0, 0, width, height)
        val region = Region().apply { setPath(path, clip) }
        val iterator = RegionIterator(region)
        val rect = Rect()
        val rectangles = mutableListOf<Int>()
        while (iterator.next(rect)) {
            rectangles.add(rect.left)
            rectangles.add(rect.top)
            rectangles.add(rect.right)
            rectangles.add(rect.bottom)
        }
        return rectangles.toIntArray().also { cornerClip = CornerClip(width, height, radius, it) }
    }

    /**
     * Invalidates the current layout and triggers a layout update.
     */
    open fun invalidateLayout() {
        shouldInvalidateLayout = true
    }

    /**
     * Calculates the object bounds and updates its rendering layout.
     */
    open fun layout(renderer: Renderer) {
        getBounds(bounds)
        renderer.layout(this)
        shouldInvalidateLayout = false
    }

    /**
     * Draws the screen object.
     */
    open fun draw(renderer: Renderer) {
        try {
            renderer.draw(this)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to draw screen object: type=$type, id=$id", e)
        }
    }

    protected open fun getBounds(rect: Rect) {
        getBounds(rect, frame.width(), frame.height())
    }

    protected fun getBounds(
        rect: Rect,
        requestedWidth: Int,
        requestedHeight: Int,
    ) {
        if (parent == null) {
            rect.set(0, 0, requestedWidth, requestedHeight)
        } else {
            val width =
                if (requestedWidth <= 0) {
                    max(
                        (
                            parent?.bounds?.width()
                                ?: 0
                        ) - layoutMargin.left - layoutMargin.right + requestedWidth,
                        0,
                    )
                } else {
                    requestedWidth
                }
            val height =
                if (requestedHeight <= 0) {
                    max(
                        (
                            parent?.bounds?.height()
                                ?: 0
                        ) - layoutMargin.top - layoutMargin.bottom + requestedHeight,
                        0,
                    )
                } else {
                    requestedHeight
                }
            val parentX = parent?.bounds?.left ?: 0
            val parentWidth = parent?.bounds?.width() ?: 0
            val x =
                when (horizontalAlignment) {
                    HORIZONTAL_ALIGNMENT_CENTER -> {
                        parentX + (parentWidth - width) / 2
                    }

                    HORIZONTAL_ALIGNMENT_RIGHT -> {
                        parentX + (parentWidth - width) - layoutMargin.right
                    }

                    else -> {
                        parentX + frame.left + layoutMargin.left
                    }
                }
            val parentY = parent?.bounds?.top ?: 0
            val parentHeight = parent?.bounds?.height() ?: 0
            val y =
                when (verticalAlignment) {
                    VERTICAL_ALIGNMENT_MIDDLE -> {
                        parentY + (parentHeight - height) / 2
                    }

                    VERTICAL_ALIGNMENT_BOTTOM -> {
                        parentY + (parentHeight - height) - layoutMargin.bottom
                    }

                    else -> {
                        parentY + frame.top + layoutMargin.top
                    }
                }
            rect.set(x, y, x + width, y + height)
        }
    }

    /**
     * Returns this object if its identifier matches, or `null`. Containers also search their descendants.
     */
    open fun findById(id: String): ScreenObject? {
        if (this.id == id) {
            return this
        }
        return null
    }

    private class CornerClip(
        val width: Int,
        val height: Int,
        val radius: Float,
        val rectangles: IntArray,
    )

    companion object {
        private val TAG = ScreenObject::class.java.simpleName
        const val HORIZONTAL_ALIGNMENT_LEFT = 0
        const val HORIZONTAL_ALIGNMENT_CENTER = 1
        const val HORIZONTAL_ALIGNMENT_RIGHT = 2

        const val VERTICAL_ALIGNMENT_TOP = 0
        const val VERTICAL_ALIGNMENT_MIDDLE = 1
        const val VERTICAL_ALIGNMENT_BOTTOM = 2
    }
}
