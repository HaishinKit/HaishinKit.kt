package com.haishinkit.graphics

/**
 * A value that specifies how the video is displayed within a layer’s bounds.
 */
@Suppress("UNUSED")
enum class VideoGravity(
    val rawValue: Int,
) {
    RESIZE(0),
    RESIZE_ASPECT(1),
    RESIZE_ASPECT_FILL(2),
}
