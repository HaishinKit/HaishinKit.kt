package com.haishinkit.media

import com.haishinkit.screen.Screen

/**
 * Produces media buffers and a composited video screen for registered outputs.
 */
interface MediaOutputDataSource {
    /**
     * Whether the source provides audio.
     */
    val hasAudio: Boolean

    /**
     * Whether the source provides video.
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
     * A buffer delivery already in progress can still include this output.
     */
    fun unregisterOutput(output: MediaOutput)
}
