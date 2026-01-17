package com.haishinkit.media

import kotlinx.serialization.Serializable

/**
 * Constraints on the audio mixier settings.
 */
@Serializable
data class AudioMixerSettings(
    /**
     * Specifies the muted that indicates whether the audio output is muted.
     */
    val isMuted: Boolean = false
)
