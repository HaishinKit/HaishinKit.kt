package com.haishinkit.codec

import android.media.MediaCodecList

object CodecCapabilities {
    private val mediaCodecList: MediaCodecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    fun isCodecSupportedByType(
        mode: Int,
        type: String,
    ): Boolean =
        when (mode) {
            Codec.MODE_ENCODE -> {
                mediaCodecList.codecInfos.any {
                    it.isEncoder && it.supportedTypes.contains(type)
                }
            }

            Codec.MODE_DECODE -> {
                mediaCodecList.codecInfos.any {
                    !it.isEncoder && it.supportedTypes.contains(type)
                }
            }

            else -> false
        }
}
