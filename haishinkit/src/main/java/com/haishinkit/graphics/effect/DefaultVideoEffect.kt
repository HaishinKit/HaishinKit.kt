package com.haishinkit.graphics.effect

/**
 * Renders video without applying a visual effect.
 */
class DefaultVideoEffect private constructor(
    override val name: String = "default",
) : VideoEffect {
    companion object {
        val shared = DefaultVideoEffect()
    }
}
