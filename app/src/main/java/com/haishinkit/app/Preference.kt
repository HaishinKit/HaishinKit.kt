package com.haishinkit.app

import android.net.Uri
import androidx.core.net.toUri

data class Preference(
    var streamURL: String,
    var streamName: String,
) {
    fun toStreamUri(): Uri {
        val uri = streamURL.toUri().normalizeScheme()
        require(uri.scheme in setOf("rtmp", "rtmps", "srt")) { "Enter an rtmp://, rtmps:// or srt:// stream URL." }
        require(uri.scheme == "srt" || !uri.host.isNullOrBlank()) { "The stream URL must include a host." }
        return if (uri.scheme == "srt") uri else ("$streamURL/$streamName").toUri().normalizeScheme()
    }

    companion object {
        var shared =
            Preference(
                "rtmp://192.168.1.7/live",
                "live",
            )
    }
}
