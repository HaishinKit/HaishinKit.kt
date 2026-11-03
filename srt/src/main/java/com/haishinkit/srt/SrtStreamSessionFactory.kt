package com.haishinkit.srt

import android.content.Context
import android.net.Uri
import com.haishinkit.stream.StreamSession
import com.haishinkit.stream.StreamSessionFactory

/**
 * Creates MPEG-TS publishing and playback sessions for `srt` URIs.
 *
 * Register with [StreamSession.Builder.registerFactory] before building a session.
 * Pass the complete SRT URI, including socket options such as `mode=listener` or
 * `streamid`. No separate stream name is appended. Transport mode is independent
 * of [StreamSession.Mode]: callers and listeners can both publish or play.
 * Closing stops media and transport; the same session may reconnect. Session-owned
 * monitoring ends on close or failure. Cancellation of connect closes its attempt
 * and propagates cancellation. Observe the returned stream's failure for media errors.
 */
object SrtStreamSessionFactory : StreamSessionFactory {
    /** URI schemes handled by this factory. */
    override val protocols = listOf("srt")

    /**
     * Creates an idle session without network I/O.
     *
     * @param application Android context used for media codecs and rendering.
     * @param uri Complete SRT endpoint URI, with optional socket configuration.
     * @param mode Whether to publish local media or play incoming MPEG-TS.
     * @return A reusable session exposing an [SrtStream].
     */
    override fun create(
        application: Context,
        uri: Uri,
        mode: StreamSession.Mode,
    ): StreamSession =
        SrtStreamSession(application, uri, mode)
}
