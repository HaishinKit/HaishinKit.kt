package com.haishinkit.screen.scene

import kotlinx.serialization.Serializable

@Serializable
data class SceneDocument(
    val version: Int,
    val scenes: List<Scene>,
)
