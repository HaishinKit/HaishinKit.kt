package com.haishinkit.rtc

import android.content.Context
import android.net.Uri
import com.haishinkit.stream.StreamSession
import com.haishinkit.stream.StreamSessionFactory

/** Registers HTTP(S) endpoints as WHIP for PUBLISH and WHEP for PLAYBACK. */
class HttpStreamSessionFactory(private val configuration: RtcConfiguration = RtcConfiguration()) : StreamSessionFactory {
    override val protocols = listOf("http", "https")

    override fun create(
        application: Context,
        uri: Uri,
        mode: StreamSession.Mode,
    ): RtcSession = RtcSession(application, uri, mode, configuration)
}
