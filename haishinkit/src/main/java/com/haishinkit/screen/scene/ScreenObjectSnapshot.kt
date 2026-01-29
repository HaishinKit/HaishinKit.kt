package com.haishinkit.screen.scene

import com.haishinkit.screen.EdgeInsets
import kotlinx.serialization.Serializable

@Serializable
data class ScreenObjectSnapshot(
    val type: String,
    val frame: Rect,
    val layoutMargin: EdgeInsets,
    val horizontalAlignment: Int,
    val verticalAlignment: Int,
    val elements: Map<String, String>,
    val children: List<ScreenObjectSnapshot>,
) {
    @Serializable
    data class Rect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )
}
