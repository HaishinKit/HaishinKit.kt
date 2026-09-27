package com.haishinkit.graphics.effect

import com.haishinkit.graphics.glsl.ShaderStage
import com.haishinkit.graphics.glsl.Uniform

/**
 * An object that provides a mosaic effect.
 */
class MosaicVideoEffect(
    override val name: String = "mosaic",
) : VideoEffect {
    /**
     * The grid scale passed to the mosaic shader; defaults to 32.
     */
    @Uniform(binding = 0, shaderStage = ShaderStage.FRAGMENT)
    var mosaicScale = 32.0f
}
