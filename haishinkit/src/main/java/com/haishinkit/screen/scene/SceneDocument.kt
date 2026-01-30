package com.haishinkit.screen.scene

import kotlinx.serialization.Serializable

/**
 * Represents a document that contains multiple scenes.
 *
 * This class serves as the root structure for serialization and deserialization.
 * It holds versioning information to support future format changes,
 * along with a collection of scenes defined in the document.
 *
 * @property version
 * The format version of the scene document.
 * Used for compatibility checks and migration when loading.
 *
 * @property scenes
 * A list of scenes included in this document.
 */
@Serializable
data class SceneDocument(
    val version: Int,
    val scenes: List<Scene>,
)
