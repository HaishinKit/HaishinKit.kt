package com.haishinkit.graphics

import javax.microedition.khronos.opengles.GL10

@Suppress("UNUSED")
enum class ResampleFilter(
    val rawValue: Int,
    val glValue: Int,
) {
    LINEAR(0, GL10.GL_LINEAR),
    NEAREST(1, GL10.GL_NEAREST),
}
