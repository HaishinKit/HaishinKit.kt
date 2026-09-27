package com.haishinkit.screen.scene

import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.ScreenObjectFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Loads scene documents and applies their layouts to a screen.
 *
 * @property screen The screen whose children are replaced by [transition] and captured by [write].
 */
class SceneManager(val screen: Screen) {
    @OptIn(ExperimentalSerializationApi::class)
    private val format =
        Json {
            namingStrategy = JsonNamingStrategy.SnakeCase
        }

    private var document: SceneDocument? = null
    private var snapshotFactory: ScreenObjectSnapshotFactory = ScreenObjectSnapshotFactory()
    private var screenObjectFactory: ScreenObjectFactory = ScreenObjectFactory()

    /**
     * Registers a creator for a custom screen object type used when restoring scenes.
     */
    fun register(
        type: String,
        creator: (ScreenObjectSnapshot) -> ScreenObject,
    ) {
        screenObjectFactory.register(type, creator)
    }

    /**
     * Replaces the screen's children with the selected scene's restored children.
     *
     * @param index The zero-based scene index in the document loaded by [read].
     * @throws IndexOutOfBoundsException If no document is loaded or the index is outside its scene list.
     */
    fun transition(index: Int) {
        val scene = document?.scenes?.get(index)
        if (scene == null) {
            throw IndexOutOfBoundsException()
        } else {
            val screenObject = screenObjectFactory.create(scene.screen)
            (screenObject as? ScreenObjectContainer)?.let {
                screen.transition(it)
            }
        }
    }

    /**
     * Decodes a JSON scene document using snake_case property names.
     *
     * Loading a document does not change the screen until [transition] is called.
     *
     * @throws kotlinx.serialization.SerializationException If the document cannot be decoded.
     * @throws IllegalArgumentException If the decoded values are invalid.
     */
    fun read(text: String) {
        document = format.decodeFromString<SceneDocument>(text)
    }

    /**
     * Serializes the current screen as a version 1 JSON document containing one unnamed scene.
     *
     * This captures the current screen rather than re-encoding all scenes from the loaded document.
     */
    fun write(): String {
        val snapshot = snapshotFactory.create(screen)
        val document = SceneDocument(1, listOf(Scene("", snapshot)))
        return format.encodeToString(document)
    }
}
