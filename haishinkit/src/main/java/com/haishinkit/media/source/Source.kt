package com.haishinkit.media.source

import com.haishinkit.media.MediaMixer

/**
 * A media capture source managed by a [MediaMixer].
 */
interface Source {
    /**
     * Opens the source for the given mixer and returns the result of initialization.
     */
    suspend fun open(mixer: MediaMixer): Result<Unit>

    /**
     * Closes the source and returns the result of releasing its capture resources.
     */
    suspend fun close(): Result<Unit>
}
