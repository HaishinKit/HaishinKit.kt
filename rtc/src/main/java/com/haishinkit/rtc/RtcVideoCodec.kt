package com.haishinkit.rtc

/** One codec per RTC video track. VP9 uses profile 0 (8-bit, single spatial layer). */
enum class RtcVideoCodec(val mime: String, internal val rtpName: String) {
    H264("video/avc", "H264"),
    VP8("video/x-vnd.on2.vp8", "VP8"),
    VP9("video/x-vnd.on2.vp9", "VP9"),
    ;

    internal companion object {
        fun fromMime(mime: String): RtcVideoCodec =
            entries.firstOrNull { it.mime == mime }
                ?: throw IllegalArgumentException("Unsupported RTC video codec: $mime")
    }
}
