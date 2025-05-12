package com.haishinkit.screen

import android.graphics.Rect
import android.opengl.GLES20
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import kotlin.math.max

/**
 * The ScreenObject class is the abstract class for all objects that are rendered on the screen.
 */
@Suppress("MemberVisibilityCanBePrivate")
abstract class ScreenObject(
    val target: Int = GLES20.GL_TEXTURE_2D,
) {
    open var id: Int = -1
        internal set

    /**
     * The screen object container that contains this screen object
     */
    open var parent: ScreenObjectContainer? = null
        internal set(value) {
            (root as? Screen)?.unbind(this)
            field = value
            (root as? Screen)?.bind(this)
        }

    /**
     * Specifies the frame rectangle.
     */
    open var frame = Rect(0, 0, 0, 0)
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * The bounds rectangle.
     */
    val bounds = Rect(0, 0, 0, 0)

    /**
     * Specifies the default spacing to laying out content in the screen object.
     */
    val layoutMargins: EdgeInsets = EdgeInsets(0, 0, 0, 0)

    /**
     * The mvp matrix.
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
     * Specifies the video effect such as a monochrome, a sepia.
     */
    var videoEffect: VideoEffect = DefaultVideoEffect.shared

    /**
     * Specifies the visibility of the object.
     */
    open var isVisible = true

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

    /**
     * Invalidates the current layout and triggers a layout update.
     */
    open fun invalidateLayout() {
        shouldInvalidateLayout = true
    }

    /**
     * Layouts the screen object.
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
        renderer.draw(this)
    }

    protected fun getBounds(rect: Rect) {
        if (parent == null) {
            rect.set(0, 0, frame.width(), frame.height())
        } else {
            val width =
                if (frame.width() <= 0) {
                    max(
                        (
                            parent?.bounds?.width()
                                ?: 0
                        ) - layoutMargins.left - layoutMargins.right + frame.width(),
                        0,
                    )
                } else {
                    frame.width()
                }
            val height =
                if (frame.height() <= 0) {
                    max(
                        (
                            parent?.bounds?.height()
                                ?: 0
                        ) - layoutMargins.top - layoutMargins.bottom + frame.height(),
                        0,
                    )
                } else {
                    frame.height()
                }
            val parentX = parent?.frame?.left ?: 0
            val parentWidth = parent?.bounds?.width() ?: 0
            val x =
                when (horizontalAlignment) {
                    HORIZONTAL_ALIGNMENT_CENTER -> {
                        parentX + (parentWidth - width) / 2
                    }

                    HORIZONTAL_ALIGNMENT_RIGHT -> {
                        parentX + (parentWidth - width) - layoutMargins.right
                    }

                    else -> {
                        parentX + frame.left + layoutMargins.left
                    }
                }
            val parentY = parent?.frame?.top ?: 0
            val parentHeight = parent?.bounds?.height() ?: 0
            val y =
                when (verticalAlignment) {
                    VERTICAL_ALIGNMENT_MIDDLE -> {
                        parentY + (parentHeight - height) / 2
                    }

                    VERTICAL_ALIGNMENT_BOTTOM -> {
                        parentY + (parentHeight - height) - layoutMargins.bottom
                    }

                    else -> {
                        parentY + frame.top + layoutMargins.top
                    }
                }
            rect.set(x, y, x + width, y + height)
        }
    }

    companion object {
        const val HORIZONTAL_ALIGNMENT_LEFT = 0
        const val HORIZONTAL_ALIGNMENT_CENTER = 1
        const val HORIZONTAL_ALIGNMENT_RIGHT = 2

        const val VERTICAL_ALIGNMENT_TOP = 0
        const val VERTICAL_ALIGNMENT_MIDDLE = 1
        const val VERTICAL_ALIGNMENT_BOTTOM = 2
    }
}
