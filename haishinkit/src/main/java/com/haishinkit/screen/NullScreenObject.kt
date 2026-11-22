package com.haishinkit.screen

import com.haishinkit.screen.scene.ScreenObjectSnapshot
import com.haishinkit.screen.scene.detachedCopy

internal class NullScreenObject(
    snapshot: ScreenObjectSnapshot,
) : ScreenObject(snapshot.id) {
    val snapshot: ScreenObjectSnapshot = snapshot.detachedCopy()

    override val type: String = TYPE

    override var elements: Map<String, String>
        get() = emptyMap()
        set(value) {}

    companion object {
        const val TYPE: String = "null"
    }
}
