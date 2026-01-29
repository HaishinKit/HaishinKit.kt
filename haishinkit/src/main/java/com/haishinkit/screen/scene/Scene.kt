package com.haishinkit.screen.scene

import kotlinx.serialization.Serializable

@Serializable
data class Scene(
    val name: String,
    val screen: ScreenObjectSnapshot,
)
