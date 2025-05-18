package com.haishinkit.rtmp

import android.content.Context
import android.net.Uri
import com.haishinkit.stream.StreamSession
import com.haishinkit.stream.StreamSessionFactory

object RtmpStreamSessionFactory : StreamSessionFactory {
    override val protocols = listOf<String>("rtmp", "rtmps")

    override fun create(
        context: Context,
        uri: Uri,
    ): StreamSession = RtmpStreamSession(context, uri)
}
