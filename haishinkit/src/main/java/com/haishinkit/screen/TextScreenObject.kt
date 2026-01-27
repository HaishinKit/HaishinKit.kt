package com.haishinkit.screen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import kotlin.math.max

/**
 * An object that manages offscreen rendering a text source.
 */
@Suppress("MemberVisibilityCanBePrivate")
class TextScreenObject : ImageScreenObject() {
    private interface Keys {
        companion object {
            const val VALUE = "value"
            const val COLOR = "color"
            const val SIZE = "size"
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
     * Specifies the text color.
     */
    var color: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * Specifies the text size.
     */
    var size: Float = 15f
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
                put(Keys.SIZE, this@TextScreenObject.size.toString())
            }
        }
        set(value) {
            this.value = value[Keys.VALUE] ?: ""
            this.color = Color.parseColor(value[Keys.COLOR] ?: "#FFFFFFFF")
            this.size = value[Keys.SIZE]?.toFloat() ?: 0f
        }

    private val paint by lazy {
        Paint(ANTI_ALIAS_FLAG).apply {
            textSize = this@TextScreenObject.size
            color = this@TextScreenObject.color
            textAlign = Paint.Align.LEFT
        }
    }

    private var canvas: Canvas? = null
    private var textBounds = Rect()

    override fun layout(renderer: Renderer) {
        paint.getTextBounds(value, 0, value.length, textBounds)
        frame.set(textBounds)
        if (bitmap?.width != textBounds.width() || bitmap?.height != textBounds.height()) {
            bitmap =
                createBitmap(max(textBounds.width(), 1), max(textBounds.height(), 1)).apply {
                    canvas = Canvas(this)
                }
        }
        bitmap?.eraseColor(Color.TRANSPARENT)
        canvas?.drawText(value, -textBounds.left.toFloat(), -textBounds.top.toFloat(), paint)
        super.layout(renderer)
    }

    companion object {
        const val TYPE: String = "text"
        private val TAG = VideoScreenObject::class.java.simpleName
    }
}
