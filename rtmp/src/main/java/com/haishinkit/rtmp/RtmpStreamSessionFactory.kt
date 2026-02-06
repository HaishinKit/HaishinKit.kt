package com.haishinkit.rtmp

import android.content.Context
import android.net.Uri
import com.haishinkit.stream.StreamSession
import com.haishinkit.stream.StreamSessionFactory

object RtmpStreamSessionFactory : StreamSessionFactory {
    override val protocols = listOf("rtmp", "rtmps")

    override fun create(
        context: Context,
        uri: Uri,
        mode: StreamSession.Mode,
    ): StreamSession = RtmpStreamSession(context, uri, mode)
}
