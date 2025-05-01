package com.haishinkit.graphics

enum class ImageOrientation(
    val rawValue: Int,
) {
    UP(1),
    DOWN(2),
    LEFT(3),
    RIGHT(4),
    UP_MIRRORED(5),
    DOWN_MIRRORED(6),
    LEFT_MIRRORED(7),
    RIGHT_MIRRORED(8),
}
