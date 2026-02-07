package com.haishinkit.app

import android.net.Uri
import androidx.core.net.toUri

data class Preference(
    var rtmpURL: String,
    var streamName: String,
) {
    fun toRtmpUrl(): Uri {
        return ("$rtmpURL/$streamName").toUri()
    }

    companion object {
        var shared =
            Preference(
                "rtmp://192.168.1.16/live",
                "live",
            )
    }
}
