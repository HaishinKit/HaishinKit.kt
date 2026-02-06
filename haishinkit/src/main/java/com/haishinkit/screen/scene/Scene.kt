package com.haishinkit.screen.scene

import kotlinx.serialization.Serializable

/**
 * Represents a scene configuration.
 *
 * A scene groups a named screen state that can be serialized and restored.
 * It is typically used to save, load, or switch between different screen layouts.
 *
 * @property name
 * The display name of the scene.
 *
 * @property screen
 * A snapshot of the screen state that defines the contents and layout of the scene.
 */
@Serializable
data class Scene(
    val name: String,
    val screen: ScreenObjectSnapshot,
)
