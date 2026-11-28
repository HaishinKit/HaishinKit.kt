package com.haishinkit.media.source

import com.haishinkit.media.audio.PcmAudioFormat

/** An audio source that identifies its current PCM format for rate/channel conversion. */
interface PcmAudioSource : AudioSource {
    val audioFormat: PcmAudioFormat
}
