package com.haishinkit.graphics.effect

import com.haishinkit.graphics.glsl.ShaderStage
import com.haishinkit.graphics.glsl.Uniform

/**
 * Resamples video using the Lanczos filter.
 */
class LanczosVideoEffect(
    override val name: String = "lanczos",
) : VideoEffect {
    @Uniform(binding = 0, shaderStage = ShaderStage.VERTEX)
    var texelWidth = 1f

    @Uniform(binding = 1, shaderStage = ShaderStage.VERTEX)
    var texelHeight = 1f
}
