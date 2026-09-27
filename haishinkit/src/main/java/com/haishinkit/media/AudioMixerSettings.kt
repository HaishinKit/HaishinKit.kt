package com.haishinkit.media

import kotlinx.serialization.Serializable

/**
 * Audio capture settings applied by [MediaMixer].
 */
@Serializable
data class AudioMixerSettings(
    /**
     * Whether captured audio is replaced with silence; defaults to `false`.
     */
    val isMuted: Boolean = false,
)
