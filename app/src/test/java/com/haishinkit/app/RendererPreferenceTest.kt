package com.haishinkit.app

import com.haishinkit.graphics.PixelTransform
import com.haishinkit.screen.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [23])
class RendererPreferenceTest {
    @Test
    fun savedVulkanSelectionFallsBackOnOlderAndroidWithoutLoadingNativeCode() {
        val context = RuntimeEnvironment.getApplication()
        val factory = PixelTransform.factory
        val screenFactory = Screen.factory
        try {
            RendererPreference.save(context, Renderer.VULKAN)
            assertEquals(Renderer.VULKAN, RendererPreference.selected(context))
            assertFalse(RendererPreference.isVulkanAvailable())
            RendererPreference.configure(context)
            val transform = PixelTransform.create(context)
            assertTrue(transform.javaClass.name.contains("gles"))
            transform.close()
        } finally {
            RendererPreference.save(context, Renderer.OPENGL)
            PixelTransform.factory = factory
            Screen.factory = screenFactory
        }
    }

    @Test
    fun onlyRegisteredOutputsAreStoppedBeforeScreensAreRecreated() =
        runBlocking {
            val stopped = mutableListOf<String>()
            val publish =
                object : RendererChangeParticipant {
                    override suspend fun stopForRendererChange() {
                        stopped.add("publish")
                    }
                }
            val playback =
                object : RendererChangeParticipant {
                    override suspend fun stopForRendererChange() {
                        stopped.add("playback")
                    }
                }
            try {
                RendererPreference.register(publish)
                RendererPreference.register(playback)
                RendererPreference.unregister(playback)
                RendererPreference.stopOutputs()
                assertEquals(listOf("publish"), stopped)
            } finally {
                RendererPreference.unregister(publish)
                RendererPreference.unregister(playback)
            }
        }
}
