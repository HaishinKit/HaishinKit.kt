package com.haishinkit.screen

import com.haishinkit.screen.scene.ScreenObjectSnapshot

internal class NullScreenObject(
    val snapshot: ScreenObjectSnapshot,
) : ScreenObject(snapshot.id) {
    override val type: String = TYPE

    override var elements: Map<String, String>
        get() = emptyMap()
        set(value) {}

    companion object {
        const val TYPE: String = "null"
    }
}
