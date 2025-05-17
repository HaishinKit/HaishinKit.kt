package com.haishinkit.media

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
     * Registers an output instance.
     */
    fun registerOutput(output: MediaOutput)

    /**
     * Unregisters an output instance.
     */
    fun unregisterOutput(output: MediaOutput)
}
