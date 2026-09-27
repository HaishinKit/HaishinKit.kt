package com.haishinkit.graphics

import javax.microedition.khronos.opengles.GL10

/**
 * Texture sampling filters used when scaling images.
 *
 * @property rawValue The library's numeric filter identifier.
 * @property glValue The corresponding OpenGL texture filter constant.
 */
@Suppress("UNUSED")
enum class ResampleFilter(
    val rawValue: Int,
    val glValue: Int,
) {
    LINEAR(0, GL10.GL_LINEAR),
    NEAREST(1, GL10.GL_NEAREST),
}
