package com.haishinkit.app

data class Preference(
    var rtmpURL: String,
    var streamName: String,
) {
    companion object {
        var shared =
            Preference(
                "rtmp://192.168.1.14/live",
                "live",
            )
    }
}
