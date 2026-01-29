package com.haishinkit.screen

import kotlinx.serialization.Serializable

/**
 * Represents inset distances from each edge of a rectangular area.
 *
 * This class is typically used to describe padding, margins, or safe-area–like
 * offsets for screens or view layouts.
 *
 * All values are expressed in pixels.
 *
 * @property top The inset from the top edge.
 * @property left The inset from the left edge.
 * @property bottom The inset from the bottom edge.
 * @property right The inset from the right edge.
 */
@Serializable
data class EdgeInsets(
    var top: Int,
    var left: Int,
    var bottom: Int,
    var right: Int,
) {
    /**
     * Updates all inset values at once.
     *
     * This is a convenience method to avoid assigning each property individually.
     *
     * @param top The inset from the top edge.
     * @param left The inset from the left edge.
     * @param bottom The inset from the bottom edge.
     * @param right The inset from the right edge.
     */
    fun set(
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    ) {
        this.top = top
        this.left = left
        this.bottom = bottom
        this.right = right
    }

    fun set(insets: EdgeInsets) {
        top = insets.top
        left = insets.left
        bottom = insets.bottom
        right = insets.right
    }
}
