package com.haishinkit.screen

import com.haishinkit.screen.scene.ScreenObjectSnapshot

/**
 * Recreates screen objects from snapshots using built-in types and registered custom creators.
 */
class ScreenObjectFactory {
    private val creators =
        mutableMapOf<String, (ScreenObjectSnapshot) -> ScreenObject>()

    /**
     * Registers a creator for a custom type, replacing any previous creator for that type.
     *
     * Built-in types are handled directly and cannot be overridden by this registration.
     */
    fun register(
        type: String,
        creator: (ScreenObjectSnapshot) -> ScreenObject,
    ) {
        creators[type] = creator
    }

    /**
     * Recreates an object and applies the snapshot's layout and element properties.
     *
     * Screen snapshots become containers. Unknown types without a registered creator become
     * [NullScreenObject] instances.
     */
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
            isVisible = snapshot.isVisible
            verticalAlignment = snapshot.verticalAlignment
            horizontalAlignment = snapshot.horizontalAlignment
            elements = snapshot.elements
        }
    }
}
