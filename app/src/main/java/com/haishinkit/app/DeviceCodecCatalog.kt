package com.haishinkit.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList

internal enum class CodecAcceleration {
    HARDWARE,
    SOFTWARE,
    UNKNOWN,
}

internal data class DeviceCodecImplementation(
    val name: String,
    val encoder: Boolean,
    val acceleration: CodecAcceleration,
    val types: List<String>,
)

internal data class DeviceCodecFamily(
    val mime: String,
    val label: String,
    val encoders: List<DeviceCodecImplementation>,
    val decoders: List<DeviceCodecImplementation>,
)

internal object DeviceCodecCatalog {
    private val names =
        linkedMapOf(
            "video/avc" to "H.264 / AVC",
            "video/hevc" to "H.265 / HEVC",
            "video/av01" to "AV1",
            "video/x-vnd.on2.vp9" to "VP9",
            "video/x-vnd.on2.vp8" to "VP8",
            "audio/mp4a-latm" to "AAC",
            "audio/opus" to "Opus",
            "audio/mpeg" to "MP3",
            "audio/flac" to "FLAC",
            "audio/vorbis" to "Vorbis",
            "audio/ac3" to "AC-3",
            "audio/eac3" to "E-AC-3",
            "video/mp4v-es" to "MPEG-4 Visual",
            "video/mpeg2" to "MPEG-2 Video",
            "video/3gpp" to "H.263",
            "video/vvc" to "H.266 / VVC",
            "audio/raw" to "PCM",
            "audio/3gpp" to "AMR-NB",
            "audio/amr-wb" to "AMR-WB",
        )
    private val commonTypes = names.keys.take(9)

    fun read(infos: List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()): List<DeviceCodecFamily> {
        // REGULAR_CODECS excludes implementations which only work in special modes (e.g. secure-only).
        val implementations =
            infos.filterNot { it.isAlias }.map { info ->
                DeviceCodecImplementation(
                    name = info.name,
                    encoder = info.isEncoder,
                    acceleration =
                        when {
                            info.isHardwareAccelerated -> CodecAcceleration.HARDWARE
                            info.isSoftwareOnly -> CodecAcceleration.SOFTWARE
                            else -> CodecAcceleration.UNKNOWN
                        },
                    types = info.supportedTypes.map { it.lowercase(java.util.Locale.ROOT) },
                )
            }
        val types =
            (commonTypes + implementations.flatMap { it.types }).distinct()
                .filter { it.startsWith("video/") || it.startsWith("audio/") }
                .sortedWith(compareBy({ names.keys.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }, { it }))
        return types.map { mime ->
            val entries = implementations.filter { mime in it.types }.distinctBy { it.name to it.encoder }.sortedBy { it.name }
            DeviceCodecFamily(mime, names[mime] ?: mime, entries.filter { it.encoder }, entries.filterNot { it.encoder })
        }
    }
}
