package com.haishinkit.graphics.glsl

/**
 * Declares the GLSL version required by an effect.
 *
 * @property code The required GLSL version.
 */
annotation class RequirementsDirective(
    val code: VersionCode,
)
