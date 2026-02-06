package com.haishinkit.stream

import android.content.Context
import android.net.Uri

interface StreamSessionFactory {
    val protocols: List<String>

    fun create(
        application: Context,
        uri: Uri,
        mode: StreamSession.Mode,
    ): StreamSession
}
