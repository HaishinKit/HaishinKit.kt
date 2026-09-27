package com.haishinkit.graphics

internal interface FpsController {
    /**
     * The target output frame rate, in frames per second.
     */
    var frameRate: Int

    fun advanced(frameTime: Long): Boolean

    fun timestamp(frameTime: Long): Long

    fun clear()
}
