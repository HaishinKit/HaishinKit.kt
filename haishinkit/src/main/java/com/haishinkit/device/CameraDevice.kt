package com.haishinkit.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CameraDevice(
    val id: String,
    val name: String,
    val position: Position
) {
    enum class Position {
        @SerialName("front")
        FRONT,

        @SerialName("back")
        BACK,

        @SerialName("external")
        EXTERNAL,

        @SerialName("unspecified")
        UNSPECIFIED
    }
}
