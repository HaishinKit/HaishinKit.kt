package com.haishinkit.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.graphics.Rect
import kotlin.math.max

/**
 * An object that manages offscreen rendering a text source.
 */
@Suppress("MemberVisibilityCanBePrivate")
class Text : Image() {
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
            textSize = this@Text.size
            color = this@Text.color
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
                Bitmap.createBitmap(
                    max(textBounds.width(), 1),
                    max(textBounds.height(), 1),
                    Bitmap.Config.ARGB_8888
                ).apply {
                    canvas = Canvas(this)
                }
        }
        bitmap?.eraseColor(Color.TRANSPARENT)
        canvas?.drawText(value, -textBounds.left.toFloat(), -textBounds.top.toFloat(), paint)
        super.layout(renderer)
    }
}
