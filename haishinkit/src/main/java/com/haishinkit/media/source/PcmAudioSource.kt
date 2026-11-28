package com.haishinkit.media.source

import com.haishinkit.media.audio.PcmAudioFormat

/**
 * A read-based source that identifies its PCM format for rate/channel conversion.
 * Configure before opening and keep the format fixed until closed. To change it,
 * detach the source, change its format, then attach it again.
 */
interface PcmAudioSource : AudioSource {
    val audioFormat: PcmAudioFormat
}
