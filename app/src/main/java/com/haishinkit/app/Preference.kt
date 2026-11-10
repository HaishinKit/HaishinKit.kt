package com.haishinkit.app

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.haishinkit.rtc.RTCConfiguration
import com.haishinkit.rtc.RTCSession
import com.haishinkit.stream.StreamSession

data class Preference(
    var streamURL: String,
    var streamName: String,
    var whepURL: String = "",
    var bearerToken: String = "",
    var iceServers: String = "",
) {
    /** An immutable snapshot, so changing authentication or ICE settings rebuilds a session. */
    data class SessionSettings(val uri: Uri, val mode: StreamSession.Mode, val rtc: RTCConfiguration?) {
        fun create(context: Context): StreamSession =
            if (rtc != null) RTCSession(context, uri, mode, rtc) else StreamSession.Builder(context, uri).setMode(mode).build()
    }

    val isRTC: Boolean
        get() = streamURL.trim().substringBefore(':').lowercase() in setOf("http", "https")

    fun toStreamUri(mode: StreamSession.Mode = StreamSession.Mode.PUBLISH): Uri {
        val url = if (isRTC && mode == StreamSession.Mode.PLAYBACK) whepURL.trim() else streamURL.trim()
        if (isRTC && mode == StreamSession.Mode.PLAYBACK) require(url.isNotEmpty()) { "Enter the WHEP playback URL in Preference." }
        val uri = url.toUri().normalizeScheme()
        require(uri.scheme in setOf("rtmp", "rtmps", "srt", "http", "https")) { "Enter an rtmp://, rtmps://, srt://, http:// or https:// stream URL." }
        require(uri.scheme == "srt" || !uri.host.isNullOrBlank()) { "The stream URL must include a host." }
        if (isRTC && mode == StreamSession.Mode.PLAYBACK) require(uri.scheme in setOf("http", "https")) { "The WHEP playback URL must use http:// or https://." }
        return if (uri.scheme in setOf("srt", "http", "https")) uri else ("$url/$streamName").toUri().normalizeScheme()
    }

    fun sessionSettings(mode: StreamSession.Mode = StreamSession.Mode.PUBLISH): SessionSettings =
        SessionSettings(
            toStreamUri(mode),
            mode,
            if (isRTC) {
                RTCConfiguration(
                    iceServers = iceServers.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                    bearerToken = bearerToken.trim().takeIf { it.isNotEmpty() },
                )
            } else {
                null
            },
        )

    companion object {
        var shared =
            Preference(
                "rtmp://192.168.1.7/live",
                "live",
            )
    }
}
