package com.haishinkit.vulkan

import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.graphics.effect.BilinearVideoEffect
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.LanczosVideoEffect
import com.haishinkit.graphics.effect.MonochromeVideoEffect
import com.haishinkit.graphics.effect.MosaicVideoEffect
import com.haishinkit.graphics.effect.SepiaVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanEffectRegistryTest {
    private class CustomEffect(var strength: Float) : VideoEffect {
        override val name = "custom"
    }

    @Test
    fun registrationBindsCurrentParametersAndUnregisterRestoresExplicitFailure() {
        val effect = CustomEffect(0.25f)
        assertThrows(IllegalStateException::class.java) { VulkanEffectRegistry.resolve(effect) }
        try {
            VulkanEffectRegistry.register(CustomEffect::class.java) {
                VulkanShader("shaders/custom.frag.spv", parameters = floatArrayOf(it.strength))
            }
            assertArrayEquals(floatArrayOf(0.25f), VulkanEffectRegistry.resolve(effect).parameters, 0f)
            effect.strength = 0.75f
            assertArrayEquals(floatArrayOf(0.75f), VulkanEffectRegistry.resolve(effect).parameters, 0f)
        } finally {
            VulkanEffectRegistry.unregister(CustomEffect::class.java)
        }
        assertThrows(IllegalStateException::class.java) { VulkanEffectRegistry.resolve(effect) }
    }

    @Test
    fun allStandardEffectsHaveVulkanImplementationsAndMosaicBindsScale() {
        listOf(DefaultVideoEffect.shared, BicubicVideoEffect(), BilinearVideoEffect(), MonochromeVideoEffect(), SepiaVideoEffect(), LanczosVideoEffect())
            .forEach { assertTrue(VulkanEffectRegistry.resolve(it).fragmentAsset.endsWith(".spv")) }
        val effect = MosaicVideoEffect().apply { mosaicScale = 17f }
        assertArrayEquals(floatArrayOf(17f), VulkanEffectRegistry.resolve(effect).parameters, 0f)
    }

    @Test
    fun shaderRejectsOversizedOrNonFinitePushConstants() {
        assertThrows(IllegalArgumentException::class.java) { VulkanShader("custom", parameters = FloatArray(13)) }
        assertThrows(IllegalArgumentException::class.java) { VulkanShader("custom", parameters = floatArrayOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { VulkanShader("") }
    }
}
