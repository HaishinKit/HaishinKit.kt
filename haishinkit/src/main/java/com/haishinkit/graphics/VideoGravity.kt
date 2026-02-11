package com.haishinkit.graphics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Specifies how a video is scaled or stretched to fit within its container bounds.
 */
@Suppress("UNUSED")
@Serializable
enum class VideoGravity(
    /**
     * Platform-compatible integer representation of this gravity.
     *
     * This value is useful when bridging with native layers or
     * when a numeric representation is required.
     */
    val rawValue: Int,
) {
    /**
     * Stretches the video to fill the container bounds.
     *
     * The aspect ratio is not preserved.
     */
    @SerialName("resize")
    RESIZE(0),

    /**
     * Scales the video to fit within the container bounds
     * while preserving the original aspect ratio.
     *
     * Black bars may appear if the aspect ratios do not match.
     */
    @SerialName("resizeAspect")
    RESIZE_ASPECT(1),

    /**
     * Scales the video to completely fill the container bounds
     * while preserving the original aspect ratio.
     *
     * Portions of the video may be clipped if the aspect ratios do not match.
     */
    @SerialName("resizeAspectFill")
    RESIZE_ASPECT_FILL(2),
}
