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
                require(Regex("(?:^|[; ])packetization-mode=1(?:;|$)").containsMatchIn(fmtp)) { "H.264 packetization-mode=1 is required" }
            }
            active.add(section.kind)
        }
        require(active.isNotEmpty()) { "Server rejected every media track" }
        return active
    }
}
