package com.haishinkit.app

data class Preference(
    var rtmpURL: String,
    var streamName: String,
) {
    companion object {
        var shared =
            Preference(
                "rtmp://wms.hdezwebcast.com/show_hdezweblive/46640/E9XVaGtYSh?streamID=E9XVaGtYSh",
                "E9XVaGtYSh",
            )
    }
}
