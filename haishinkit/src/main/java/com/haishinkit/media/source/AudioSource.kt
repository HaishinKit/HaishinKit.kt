package com.haishinkit.media.source

import com.haishinkit.media.MediaBuffer

/**
 * An interface that captures an audio source.
 */
interface AudioSource : Source {
    /**
     * Specifies the muted indicates whether the media muted.
     */
    var isMuted: Boolean

    fun read(track: Int): MediaBuffer
}
