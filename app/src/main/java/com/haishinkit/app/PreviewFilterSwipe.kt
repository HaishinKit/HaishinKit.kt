package com.haishinkit.app

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/** Background swipes only: never reinterpret object transforms or vertical/pinch gestures. */
internal fun previewFilterStep(
    start: Offset,
    end: Offset,
    minimumDistance: Float,
    objectSelected: Boolean,
    multiplePointers: Boolean,
): Int {
    if (objectSelected || multiplePointers) return 0
    val delta = end - start
    if (abs(delta.x) < minimumDistance || abs(delta.x) < abs(delta.y) * 1.5f) return 0
    return if (delta.x < 0f) 1 else -1
}
