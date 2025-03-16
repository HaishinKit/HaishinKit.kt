package com.haishinkit.screen

import android.graphics.Bitmap

/**
 * An object that manages offscreen rendering an image source.
 */
open class ImageScreenObject : ScreenObject() {
    var bitmap: Bitmap? = null
        set(value) {
            if (field == value) return
            field?.recycle()
            field = value
            invalidateLayout()
        }

    init {
        matrix[5] = matrix[5] * -1
    }
}
