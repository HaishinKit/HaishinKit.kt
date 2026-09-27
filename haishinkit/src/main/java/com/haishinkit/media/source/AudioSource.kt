package com.haishinkit.media.source

import com.haishinkit.media.MediaBuffer

/**
 * Captures audio buffers for a media mixer.
 */
interface AudioSource : Source {
    /**
     * Whether captured audio is replaced with silence.
     */
    var isMuted: Boolean

    /**
     * Reads audio for the given track.
     *
     * The returned payload may be reused by subsequent reads. Copy it if it must be retained.
     *
     * @param track The track identifier stored in the returned buffer.
     */
    fun read(track: Int): MediaBuffer
}
