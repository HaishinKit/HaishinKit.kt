package com.haishinkit.codec

import android.media.MediaCodecList

object CodecCapabilities {
    // MediaCodecList already caches the system catalog; avoid retaining a second snapshot.
    private val codecInfos
        get() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos

    fun isCodecSupportedByType(
        mode: Int,
        type: String,
    ): Boolean =
        when (mode) {
            Codec.MODE_ENCODE -> {
                codecInfos.any {
                    it.isEncoder && it.supportedTypes.contains(type)
                }
            }

            Codec.MODE_DECODE -> {
                codecInfos.any {
                    !it.isEncoder && it.supportedTypes.contains(type)
                }
            }

            else -> false
        }
}
