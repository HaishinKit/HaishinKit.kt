package com.haishinkit.media

import kotlinx.serialization.Serializable

/** Per-input gain; muting consumes queued input so unmuting never replays old audio. */
@Serializable
data class AudioMixerTrackSettings(val volume: Float = 1f, val isMuted: Boolean = false) {
    init {
        require(volume.isFinite() && volume in 0f..4f)
    }
}
