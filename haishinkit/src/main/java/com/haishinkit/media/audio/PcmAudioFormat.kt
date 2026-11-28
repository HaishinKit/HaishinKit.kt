package com.haishinkit.media.audio

import android.media.AudioFormat

/** Interleaved input PCM. Output to encoders is always PCM16. */
data class PcmAudioFormat(val sampleRate: Int, val channels: Int, val encoding: Int = AudioFormat.ENCODING_PCM_16BIT) {
    init {
        require(sampleRate in 8000..192000)
        require(channels in 1..2) { "Audio mixing supports mono and stereo inputs" }
        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
    }
}
