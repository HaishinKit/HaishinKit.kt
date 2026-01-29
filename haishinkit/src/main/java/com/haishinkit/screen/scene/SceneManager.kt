package com.haishinkit.screen.scene

import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.ScreenObjectFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

class SceneManager(val screen: Screen) {
    @OptIn(ExperimentalSerializationApi::class)
    private val format =
        Json {
            namingStrategy = JsonNamingStrategy.SnakeCase
        }

    private var document: SceneDocument? = null
    private var snapshotFactory: ScreenObjectSnapshotFactory = ScreenObjectSnapshotFactory()
    private var screenObjectFactory: ScreenObjectFactory = ScreenObjectFactory()

    fun register(
        type: String,
        creator: (ScreenObjectSnapshot) -> ScreenObject,
    ) {
        screenObjectFactory.register(type, creator)
    }

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

    fun read(text: String) {
        document = format.decodeFromString<SceneDocument>(text)
    }

    fun write(): String {
        val snapshot = snapshotFactory.create(screen)
        val document = SceneDocument(1, listOf(Scene("", snapshot)))
        return format.encodeToString(document)
    }
}
