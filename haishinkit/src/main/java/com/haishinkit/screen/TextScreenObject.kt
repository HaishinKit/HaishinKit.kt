package com.haishinkit.screen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders text as a bitmap in an offscreen composition.
 */
@Suppress("MemberVisibilityCanBePrivate")
class TextScreenObject(
    id: String? = null,
) : ImageScreenObject(id) {
    private interface Keys {
        companion object {
            const val VALUE = "value"
            const val COLOR = "color"
            const val FONT_SIZE = "fontSize"
        }
    }

    override val type: String = TYPE

    /**
     * Specifies the text value.
     */
    var value: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * The text color as an Android ARGB color integer; defaults to white.
     */
    var color: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * The text size, in pixels; defaults to 32.
     */
    var size: Float = 32f
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    override var elements: Map<String, String>
        get() {
            return buildMap {
                put(Keys.VALUE, value)
                put(Keys.COLOR, String.format("#%08X", color))
                put(Keys.FONT_SIZE, this@TextScreenObject.size.toString())
            }
        }
        set(value) {
            this.value = value[Keys.VALUE] ?: ""
            this.color = (value[Keys.COLOR] ?: "#FFFFFFFF").toColorInt()
            (value[Keys.FONT_SIZE] ?: "32").toFloatOrNull()?.let { this.size = it }
        }

    private val paint =
        TextPaint(ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
    private var textLayout: StaticLayout? = null

    override fun getBounds(rect: Rect) {
        if (value.isEmpty()) {
            textLayout = null
            rect.setEmpty()
            return
        }
        super.getBounds(rect)
        paint.textSize = size
        val measured = measureText(rect.width(), rect.height(), paint)
        paint.color = color
        textLayout = makeTextLayout(max(measured.width, 1))
        if (measured.width <= 0 || measured.height <= 0) {
            rect.setEmpty()
        } else {
            super.getBounds(rect, measured.width, measured.height)
        }
    }

    internal fun measureText(
        width: Int,
        height: Int,
    ): Size {
        val measurementPaint = TextPaint(ANTI_ALIAS_FLAG)
        measurementPaint.typeface = Typeface.DEFAULT_BOLD
        measurementPaint.textSize = size
        return measureText(width, height, measurementPaint)
    }

    private fun measureText(
        width: Int,
        height: Int,
        textPaint: TextPaint,
    ): Size {
        if (value.isEmpty()) return Size(0, 0)
        val availableWidth = max(width, 1)
        val layout = makeTextLayout(availableWidth, textPaint)
        val textWidth = (0 until layout.lineCount).maxOfOrNull { ceil(layout.getLineWidth(it)).toInt() } ?: 0
        return Size(min(textWidth, availableWidth), min(layout.height, max(height, 0)))
    }

    @Suppress("DEPRECATION")
    private fun makeTextLayout(
        width: Int,
        textPaint: TextPaint = paint,
    ): StaticLayout = StaticLayout(value, textPaint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)

    override fun layout(renderer: Renderer) {
        getBounds(bounds)
        val width = max(bounds.width(), 1)
        val height = max(bounds.height(), 1)
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap = createBitmap(width, height)
        }
        bitmap?.let {
            it.eraseColor(Color.TRANSPARENT)
            textLayout?.draw(Canvas(it))
        }
        renderer.layout(this)
        shouldInvalidateLayout = false
    }

    companion object {
        const val TYPE: String = "text"
    }
}
