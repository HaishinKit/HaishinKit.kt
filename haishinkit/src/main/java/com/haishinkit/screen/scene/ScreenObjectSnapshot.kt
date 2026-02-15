package com.haishinkit.screen.scene

import com.haishinkit.screen.EdgeInsets
import kotlinx.serialization.Serializable

/**
 * Represents a serializable snapshot of a screen object.
 *
 * This class captures the state of a screen object at a specific point in time
 * for persistence or restoration purposes. It is independent from runtime
 * screen object instances and is used as a data-only representation.
 *
 * Screen objects can form a hierarchical structure, expressed through
 * nested [children] snapshots.
 *
 * @property type
 * The type identifier of the screen object.
 * This value is typically used to determine which screen object to recreate.
 *
 * @property id
 * The unique identifier of this screen object.
 *
 * @property size
 * The position and size of the screen object within its parent coordinate space.
 *
 * @property isVisible
 * The visibility of the screen object.
 *
 * @property layoutMargin
 * The margin applied to the screen object when performing layout calculations.
 *
 * @property horizontalAlignment
 * The horizontal alignment of the screen object within its parent.
 *
 * @property verticalAlignment
 * The vertical alignment of the screen object within its parent.
 *
 * @property elements
 * A map of element-specific properties represented as key-value pairs.
 * Used to store extensible or type-specific attributes.
 *
 * @property children
 * Child screen object snapshots that form a hierarchical screen structure.
 */
@Serializable
data class ScreenObjectSnapshot(
    val type: String,
    val id: String,
    val size: Size,
    val isVisible: Boolean,
    val layoutMargin: EdgeInsets,
    val horizontalAlignment: Int,
    val verticalAlignment: Int,
    val elements: Map<String, String>,
    val children: List<ScreenObjectSnapshot>,
) {
    @Serializable
    data class Size(
        val width: Int,
        val height: Int,
    )
}
