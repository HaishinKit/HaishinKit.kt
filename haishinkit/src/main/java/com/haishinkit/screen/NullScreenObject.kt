package com.haishinkit.screen

internal class NullScreenObject(
    id: String? = null,
) : ScreenObject(id) {
    override val type: String = TYPE

    override var elements: Map<String, String>
        get() = emptyMap()
        set(value) {}

    companion object {
        const val TYPE: String = "null"
    }
}
