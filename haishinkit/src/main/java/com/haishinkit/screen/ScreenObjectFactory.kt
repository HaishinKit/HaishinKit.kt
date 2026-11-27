package com.haishinkit.screen

import android.content.Context
import com.haishinkit.screen.scene.ScreenObjectSnapshot

/**
 * Recreates screen objects from snapshots using built-in types and registered custom creators.
 */
class ScreenObjectFactory(private val context: Context? = null) {
    private val creators =
        mutableMapOf<String, (ScreenObjectSnapshot) -> ScreenObject>()

    /**
     * Registers a creator for a custom type, replacing any previous creator for that type.
     *
     * Built-in types are handled directly, except asset creators may override file playback for custom asset formats.
     * Pass a Context to the factory to enable built-in local video assets; without one they remain opaque.
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

            AssetScreenObject.TYPE ->
                creators[AssetScreenObject.TYPE]?.invoke(snapshot)
                    ?: if (context != null && snapshot.elements["format"] in listOf(null, "video")) AssetScreenObject(context, snapshot.id) else NullScreenObject(snapshot)
            ImageScreenObject.TYPE -> ImageScreenObject(snapshot.id)
            VideoScreenObject.TYPE -> VideoScreenObject(snapshot.id)
            TextScreenObject.TYPE -> TextScreenObject(snapshot.id)
            else -> {
                creators[snapshot.type]?.invoke(snapshot) ?: NullScreenObject(snapshot)
            }
        }.apply {
            layoutMargin.set(snapshot.layoutMargin)
            frame.set(
                0,
                0,
                snapshot.size.width,
                snapshot.size.height,
            )
            isVisible = this !is NullScreenObject && snapshot.isVisible
            cornerRadius = snapshot.cornerRadius
            verticalAlignment = snapshot.verticalAlignment.takeIf { it in 0..2 } ?: ScreenObject.VERTICAL_ALIGNMENT_TOP
            horizontalAlignment = snapshot.horizontalAlignment.takeIf { it in 0..2 } ?: ScreenObject.HORIZONTAL_ALIGNMENT_LEFT
            elements = snapshot.elements
        }
    }
}
