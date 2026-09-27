package com.haishinkit.graphics.effect

import com.haishinkit.graphics.glsl.RequirementsDirective
import com.haishinkit.graphics.glsl.VersionCode

/**
 * Resamples video using bilinear interpolation.
 */
@RequirementsDirective(VersionCode.ES300)
class BilinearVideoEffect(
    override val name: String = "bilinear",
) : VideoEffect
