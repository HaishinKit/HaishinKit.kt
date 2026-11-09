package com.haishinkit.vulkan

import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.graphics.effect.BilinearVideoEffect
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.LanczosVideoEffect
import com.haishinkit.graphics.effect.MonochromeVideoEffect
import com.haishinkit.graphics.effect.MosaicVideoEffect
import com.haishinkit.graphics.effect.SepiaVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import java.util.concurrent.ConcurrentHashMap

/** SPIR-V assets and up to twelve float push constants for a Vulkan effect. See vulkan/README.md. */
class VulkanShader(
    val fragmentAsset: String,
    val vertexAsset: String = "shaders/scene.vert.spv",
    val parameters: FloatArray = floatArrayOf(),
) {
    init {
        require(fragmentAsset.isNotBlank() && vertexAsset.isNotBlank())
        require(parameters.size <= 12 && parameters.all { it.isFinite() })
    }
}

/** Registers Vulkan implementations by effect class, so an OpenGL shader name cannot silently fall back. */
object VulkanEffectRegistry {
    private val factories = ConcurrentHashMap<Class<out VideoEffect>, (VideoEffect) -> VulkanShader>()

    fun <T : VideoEffect> register(
        type: Class<T>,
        factory: (T) -> VulkanShader,
    ) {
        factories[type] = { factory(type.cast(it)!!) }
    }

    fun unregister(type: Class<out VideoEffect>) {
        factories.remove(type)
    }

    internal fun resolve(effect: VideoEffect): VulkanShader =
        factories[effect.javaClass]?.invoke(effect) ?: when (effect) {
            is DefaultVideoEffect -> VulkanShader("shaders/default.frag.spv")
            is BicubicVideoEffect -> VulkanShader("shaders/bicubic.frag.spv")
            is BilinearVideoEffect -> VulkanShader("shaders/default.frag.spv")
            is MonochromeVideoEffect -> VulkanShader("shaders/monochrome.frag.spv")
            is SepiaVideoEffect -> VulkanShader("shaders/sepia.frag.spv")
            is MosaicVideoEffect -> VulkanShader("shaders/scene-mosaic.frag.spv", parameters = floatArrayOf(effect.mosaicScale))
            is LanczosVideoEffect -> VulkanShader("shaders/scene-lanczos.frag.spv", parameters = floatArrayOf(effect.texelWidth, effect.texelHeight))
            else -> error("No Vulkan shader registered for ${effect.javaClass.name}")
        }
}
