package com.haishinkit.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a camera device available on the system.
 *
 * This model is used across platform boundaries and therefore marked as
 * [Serializable] so that it can be encoded/decoded (e.g., JSON) when
 * communicating with other layers such as Flutter, Compose UI, or network APIs.
 *
 * @property id Unique identifier of the camera device provided by the system (e.g., Camera2 ID).
 * @property name Human readable name of the camera, generated from device characteristics.
 * @property position Physical position/type of the camera.
 */
@Serializable
data class CameraDevice(
    val id: String,
    val name: String,
    val position: Position,
) {
    /**
     * Indicates the physical location or category of the camera device.
     *
     * The value is serialized as a lowercase string to keep compatibility
     * with other platforms and external representations.
     */
    enum class Position {
        /** Camera located on the front side of the device (typically used for selfies). */
        @SerialName("front")
        FRONT,

        /** Camera located on the back side of the device (main camera). */
        @SerialName("back")
        BACK,

        /** External camera such as USB/UVC connected device. */
        @SerialName("external")
        EXTERNAL,

        /** Position could not be determined or is not provided by the system. */
        @SerialName("unspecified")
        UNSPECIFIED,
    }
}
