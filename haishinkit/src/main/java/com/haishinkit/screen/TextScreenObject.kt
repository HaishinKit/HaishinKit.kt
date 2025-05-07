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
}
