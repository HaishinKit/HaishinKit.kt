package com.haishinkit.rtmp

import android.net.Uri

internal class RtmpUri(
    private val uri: Uri,
) {
    val streamName: String
        get() {
            return uri.pathSegments.last().toString()
        }

    val tcUrl: String
        get() {
            val path = uri.pathSegments.first().toString()
            val tcUrl = uri.buildUpon().path(path)
            return tcUrl.toString()
        }

    override fun toString() = uri.toString()
}
