package com.haishinkit.screen

import com.haishinkit.screen.scene.ScreenObjectSnapshot

class ScreenObjectFactory {
    private val creators =
        mutableMapOf<String, (ScreenObjectSnapshot) -> ScreenObject>()

    fun register(
        type: String,
        creator: (ScreenObjectSnapshot) -> ScreenObject,
    ) {
        creators[type] = creator
    }

    fun create(snapshot: ScreenObjectSnapshot): ScreenObject {
        return when (snapshot.type) {
            Screen.TYPE,
            ScreenObjectContainer.TYPE,
            -> {
                ScreenObjectContainer(snapshot.id).apply {
                    for (child in snapshot.children) {
                        addChild(create(child))
                    }
                }
            }

            ImageScreenObject.TYPE -> ImageScreenObject(snapshot.id)
            VideoScreenObject.TYPE -> VideoScreenObject(snapshot.id)
            TextScreenObject.TYPE -> TextScreenObject(snapshot.id)
            else -> {
                creators[snapshot.type]?.invoke(snapshot) ?: NullScreenObject(snapshot.id)
            }
        }.apply {
            layoutMargin.set(snapshot.layoutMargin)
            frame.set(
                0,
                0,
                snapshot.size.width,
                snapshot.size.height,
            )
            verticalAlignment = snapshot.verticalAlignment
            horizontalAlignment = snapshot.horizontalAlignment
            elements = snapshot.elements
        }
    }
}
