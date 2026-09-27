package com.haishinkit.graphics.effect

import com.haishinkit.graphics.glsl.Uniform
import java.lang.reflect.Method

/**
 * Defines a shader-based video effect and its uniform bindings.
 */
interface VideoEffect {
    /**
     * The shader resource name used to load this effect.
     */
    val name: String

    /**
     * Uniform annotations discovered on the effect, sorted by binding index.
     */
    val uniforms: Array<Uniform>
        get() {
            val values = mutableListOf<Uniform>()
            for (method in javaClass.methods) {
                val uniform = method.getAnnotation(Uniform::class.java) ?: continue
                values.add(uniform)
            }
            values.sortBy { uniform -> uniform.binding }
            return values.toTypedArray()
        }

    /**
     * Uniform value accessors ordered by binding index. Binding indices must be contiguous and start at zero.
     */
    val methods: Array<Method>
        get() {
            val values = mutableMapOf<Int, Method>()
            for (method in javaClass.methods) {
                val uniform = method.getAnnotation(Uniform::class.java) ?: continue
                values[uniform.binding] = javaClass.getMethod(method.name.split("$")[0])
            }
            return MutableList(values.size) { index -> values[index]!! }.toTypedArray()
        }
}
