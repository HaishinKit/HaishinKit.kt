package com.haishinkit.app

import android.content.Context
import android.os.Build
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.vulkan.VkPixelTransform

enum class Renderer(val label: String) {
    OPENGL("OpenGL"),
    VULKAN("Vulkan"),
}

interface RendererChangeParticipant {
    suspend fun stopForRendererChange()
}

object RendererPreference {
    private const val STORE = "renderer"
    private const val KEY = "backend"
    private val participants = linkedSetOf<RendererChangeParticipant>()

    fun selected(context: Context): Renderer =
        Renderer.entries.firstOrNull { it.name == context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, null) }
            ?: Renderer.OPENGL

    fun isVulkanAvailable(): Boolean = Build.VERSION.SDK_INT >= 28 && VkPixelTransform.isSupported()

    fun save(
        context: Context,
        renderer: Renderer,
    ) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, renderer.name).apply()
    }

    fun configure(context: Context) {
        PixelTransform.factory =
            if (selected(context) == Renderer.VULKAN && isVulkanAvailable()) {
                VkPixelTransform::create
            } else {
                PixelTransform::createOpenGL
            }
    }

    fun register(participant: RendererChangeParticipant) {
        participants.add(participant)
    }

    fun unregister(participant: RendererChangeParticipant) {
        participants.remove(participant)
    }

    suspend fun stopOutputs() {
        participants.toList().forEach { it.stopForRendererChange() }
    }
}
