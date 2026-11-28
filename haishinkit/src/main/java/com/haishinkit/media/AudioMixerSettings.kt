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
    /** Fixed output rate. Configure before starting capture or registering outputs. */
    val sampleRate: Int = 48_000,
    /** Fixed output channel count: 1 for mono, 2 for stereo. */
    val channels: Int = 2,
    /** Gain and mute settings for numbered input tracks. */
    val tracks: Map<Int, AudioMixerTrackSettings> = emptyMap(),
) {
    init {
        require(sampleRate in setOf(8000, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 96000))
        require(channels in 1..2)
    }

    /** Retains the original JVM constructor, including its default-argument bridge. */
    constructor(isMuted: Boolean = false) : this(isMuted, 48_000, 2, emptyMap())

    /** Retains the original JVM copy method while preserving the additional settings. */
    fun copy(isMuted: Boolean): AudioMixerSettings = copy(isMuted, sampleRate, channels, tracks)

    companion object {
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility bridge", level = DeprecationLevel.HIDDEN)
        @Suppress("UNUSED_PARAMETER")
        fun legacyCopy(
            value: AudioMixerSettings,
            isMuted: Boolean,
            mask: Int,
            marker: Any?,
        ): AudioMixerSettings =
            value.copy(if (mask and 1 != 0) value.isMuted else isMuted)
    }
}
