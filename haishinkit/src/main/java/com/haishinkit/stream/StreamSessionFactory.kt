package com.haishinkit.stream

import android.content.Context
import android.net.Uri

/**
 * Creates stream sessions for a set of URI schemes.
 */
interface StreamSessionFactory {
    /**
     * Supported URI schemes, such as `rtmp` and `rtmps`.
     */
    val protocols: List<String>

    /**
     * Creates a session without connecting it.
     *
     * @param application The Android context for the session.
     * @param uri The complete stream URI.
     * @param mode Whether to publish media or play an incoming stream.
     */
    fun create(
        application: Context,
        uri: Uri,
        mode: StreamSession.Mode,
    ): StreamSession
}
