package com.haishinkit.graphics.glsl

/**
 * Marks a video effect property as a shader uniform.
 *
 * @property binding The zero-based uniform binding index.
 * @property shaderStage The shader stage that uses the uniform.
 */
annotation class Uniform(
    val binding: Int = 0,
    val shaderStage: ShaderStage = ShaderStage.ALL,
)
