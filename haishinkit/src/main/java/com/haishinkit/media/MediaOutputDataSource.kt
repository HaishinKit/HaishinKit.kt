package com.haishinkit.media

import com.haishinkit.screen.Screen

/**
 * Interface for classes whose instances can be output media buffer.
 */
interface MediaOutputDataSource {
    /**
     * Whether audio source is enabled or not.
     */
    val hasAudio: Boolean

    /**
     * Whether video source is enabled or not.
     */
    val hasVideo: Boolean

    /**
     * The offscreen renderer for video output.
     */
    val screen: Screen

    /**
     * Registers an output instance.
     */
    fun registerOutput(output: MediaOutput)

    /**
     * Unregisters an output instance.
     */
    fun unregisterOutput(output: MediaOutput)
}
