package com.haishinkit.app

import android.net.Uri
import androidx.core.net.toUri

data class Preference(
    var streamURL: String,
    var streamName: String,
) {
    fun toStreamUri(): Uri {
        val uri = streamURL.toUri()
        return if (uri.scheme == "srt") uri else ("$streamURL/$streamName").toUri()
    }

    companion object {
        var shared =
            Preference(
                "rtmp://192.168.1.7/live",
                "live",
            )
    }
}
