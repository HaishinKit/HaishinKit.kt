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

    fun create(data: ScreenObjectSnapshot): ScreenObject {
        return when (data.type) {
            Screen.TYPE,
            ScreenObjectContainer.TYPE,
            -> {
                ScreenObjectContainer().apply {
                    for (child in data.children) {
                        addChild(create(child))
                    }
                }
            }

            ImageScreenObject.TYPE -> ImageScreenObject()
            VideoScreenObject.TYPE -> VideoScreenObject()
            TextScreenObject.TYPE -> TextScreenObject()
            else -> {
                creators[data.type]?.invoke(data) ?: NullScreenObject()
            }
        }.apply {
            layoutMargins.set(data.layoutMargin)
            frame.set(
                data.frame.x,
                data.frame.y,
                data.frame.x + data.frame.width,
                data.frame.y + data.frame.height,
            )
            verticalAlignment = data.verticalAlignment
            horizontalAlignment = data.horizontalAlignment
            elements = data.elements
        }
    }
}
