package com.haishinkit.media.source

import com.haishinkit.media.MediaMixer

/**
 * An interface that captures a source.
 */
interface Source {
    /**
     * Open a source.
     */
    suspend fun open(mixer: MediaMixer): Result<Unit>

    /**
     * Closes a source.
     */
    suspend fun close()
}
