package com.haishinkit.rtc

import java.util.Base64

/** The answer must preserve our offered mids and payload mappings. */
internal object SDP {
    data class Media(val kind: String, val port: Int, val payloads: Set<Int>, val lines: List<String>)

    private val directions = setOf("a=sendonly", "a=recvonly", "a=sendrecv", "a=inactive")

    fun videoConfiguration(sdp: String): List<ByteArray> {
        val video = media(sdp).firstOrNull { it.kind == "video" && it.port != 0 } ?: return emptyList()
        val fmtp = video.lines.firstOrNull { it.startsWith("a=fmtp:96 ") } ?: return emptyList()
        val parameter = fmtp.substringAfter(' ').split(';').map { it.trim() }.firstOrNull { it.startsWith("sprop-parameter-sets=") } ?: return emptyList()
        return parameter.substringAfter('=').split(',').map { value ->
            Base64.getDecoder().decode(value).also {
                require(it.isNotEmpty() && it.size <= 65536 && it[0].toInt() and 31 in 7..8) { "Invalid SDP H.264 parameter set" }
            }
        }
    }

    fun media(sdp: String): List<Media> {
        require(sdp.length <= 1024 * 1024) { "SDP is too large" }
        val sections = sdp.replace("\r\n", "\n").split("\nm=")
        require(sections.first().lineSequence().first() == "v=0") { "Invalid SDP" }
        val sessionDirection = sections.first().lines().firstOrNull { it in directions }
        return sections.drop(1).map { section ->
            val lines = section.lines()
            val fields = lines.first().trim().split(Regex("\\s+"))
            require(fields.size >= 4 && fields[2] == "UDP/TLS/RTP/SAVPF") { "Unsupported SDP media transport" }
            val attributes = lines.drop(1)
            val inherited = if (attributes.none { it in directions } && sessionDirection != null) listOf(sessionDirection) else emptyList()
            Media(fields[0], fields[1].toInt(), fields.drop(3).map { it.toInt() }.toSet(), attributes + inherited)
        }
    }

    /** The initial encoder advertises and configures Baseline level 3.1 only. */
    fun validateH264Sender(
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
    ) {
        require(width > 0 && height > 0 && frameRate > 0 && bitRate in 1..14_000_000) { "Invalid H.264 level 3.1 encoding settings" }
        val columns = (width.toLong() + 15) / 16
        val rows = (height.toLong() + 15) / 16
        require(columns * rows <= 3600 && columns * rows * frameRate <= 108_000 && columns * columns <= 28_800 && rows * rows <= 28_800) {
            "Video dimensions/frame rate exceed H.264 level 3.1 limits"
        }
    }

    private fun validateH264Profile(
        parameters: Map<String, String>,
        publishing: Boolean,
    ) {
        // RFC 6184: omission means unconstrained Baseline level 1, not our level 3.1 offer.
        val value = parameters["profile-level-id"] ?: "42000a"
        require(value.matches(Regex("[0-9a-fA-F]{6}"))) { "Invalid H.264 profile-level-id" }
        val id = value.substring(0, 2).toInt(16)
        val constraints = value.substring(2, 4).toInt(16)
        val level = value.substring(4, 6).toInt(16)
        // Equivalent constrained-baseline profile patterns from RFC 6184.
        val constrainedBaseline =
            (id == 0x42 && constraints and 0x4f == 0x40) ||
                (id == 0x4d && constraints and 0x8f == 0x80) ||
                (id == 0x58 && constraints and 0xcf == 0xc0)
        require(constrainedBaseline) { "Server selected an incompatible H.264 profile" }
        require(level in setOf(10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52, 60, 61, 62)) { "Unsupported H.264 level" }
        val asymmetric = parameters["level-asymmetry-allowed"] ?: "0"
        require(asymmetric in setOf("0", "1")) { "Invalid H.264 level-asymmetry-allowed" }
        val maxReceive =
            parameters["max-recv-level"]?.let {
                require(it.matches(Regex("[0-9a-fA-F]{4}"))) { "Invalid H.264 max-recv-level" }
                val maximum = it.takeLast(2).toInt(16)
                require(maximum > level && maximum in setOf(11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52, 60, 61, 62)) { "Invalid H.264 maximum receive level" }
                maximum
            } ?: level
        if (publishing) {
            val receiveLevel = if (asymmetric == "1") maxReceive else minOf(31, level)
            require(receiveLevel >= 31) { "Server receive level is below the configured H.264 level 3.1" }
            require(level <= 31 || asymmetric == "1") { "Server changed the symmetric H.264 level" }
        } else {
            require(level <= 31) { "Server send level exceeds the offered H.264 level 3.1" }
        }
    }

    fun validateAnswer(
        sdp: String,
        publishing: Boolean,
        audio: Boolean,
        video: Boolean,
    ): Set<String> {
        val media = media(sdp)
        require(media.map { it.kind }.distinct().size == media.size) { "Duplicate media sections" }
        val active = mutableSetOf<String>()
        for (section in media) {
            val isAudio = section.kind == "audio"
            require((isAudio && audio) || (section.kind == "video" && video)) { "Unexpected answer media" }
            require("a=mid:${section.kind}" in section.lines) { "Answer changed media ID" }
            if (section.port == 0 || "a=inactive" in section.lines) continue
            val direction = section.lines.firstOrNull { it in setOf("a=sendonly", "a=recvonly", "a=sendrecv") } ?: "a=sendrecv"
            require(direction == if (publishing) "a=recvonly" else "a=sendonly") { "Answer has incompatible media direction" }
            val payload = if (isAudio) 111 else 96
            val codec = if (isAudio) "opus/48000/2" else "H264/90000"
            require(payload in section.payloads && section.lines.any { it.equals("a=rtpmap:$payload $codec", true) }) { "Server did not select $codec" }
            if (!isAudio) {
                val fmtp = section.lines.firstOrNull { it.startsWith("a=fmtp:96 ") }.orEmpty()
                val parameters =
                    fmtp.substringAfter(' ', "").split(';').associate {
                        val pair = it.trim().split('=', limit = 2)
                        pair.first().lowercase() to pair.getOrElse(1) { "" }
                    }
                require(parameters["packetization-mode"] == "1") { "H.264 packetization-mode=1 is required" }
                validateH264Profile(parameters, publishing)
            }
            active.add(section.kind)
        }
        require(active.isNotEmpty()) { "Server rejected every media track" }
        return active
    }
}
