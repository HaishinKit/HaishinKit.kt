package com.haishinkit.media.source

import com.haishinkit.codec.AudioCodec

/**
 * An interface that captures an audio source.
 */
interface AudioSource : Source {
    /**
     * Specifies the muted indicates whether the media muted.
     */
    var isMuted: Boolean

    /**
     * Registers an audio codec instance.
     */
    fun registerAudioCodec(codec: AudioCodec)

    /**
     * Unregisters an audio codec instance.
     */
    fun unregisterAudioCodec(codec: AudioCodec)
}
