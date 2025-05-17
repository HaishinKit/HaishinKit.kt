package com.haishinkit.media

interface MediaSource {
    /**
     * Whether audio source is enabled or not.
     */
    val hasAudio: Boolean

    /**
     * Whether video source is enabled or not.
     */
    val hasVideo: Boolean
}
