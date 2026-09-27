package com.haishinkit.graphics.effect

import com.haishinkit.graphics.glsl.RequirementsDirective
import com.haishinkit.graphics.glsl.VersionCode

/**
 * Resamples video using bicubic interpolation.
 */
@RequirementsDirective(VersionCode.ES300)
class BicubicVideoEffect(
    override val name: String = "bicubic",
) : VideoEffect
