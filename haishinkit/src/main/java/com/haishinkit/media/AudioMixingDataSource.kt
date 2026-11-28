package com.haishinkit.media

/** A source whose fixed mixed PCM format is applied to registered streams and recorders. */
interface AudioMixingDataSource : MediaOutputDataSource {
    val audioMixerSettings: AudioMixerSettings?
}
