package com.haishinkit.device

import kotlinx.serialization.Serializable

@Serializable
data class MicrophoneDevice(
    val productName: String,
    val address: String,
    val category: Category,
) {
    val name = category.name

    enum class Category(val rawValue: String) {
        BUILTIN("BuiltIn"),
        BLUETOOTH("Bluetooth"),
        HEADSET("Headset"),
        OTHERS("Others"),
    }
}
