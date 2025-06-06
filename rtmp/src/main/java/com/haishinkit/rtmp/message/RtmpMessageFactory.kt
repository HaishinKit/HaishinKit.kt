package com.haishinkit.rtmp.message

import androidx.core.util.Pools
import com.haishinkit.rtmp.RtmpObjectEncoding

internal class RtmpMessageFactory(
    maxPoolSize: Int,
) {
    private val user = Pools.SimplePool<RtmpMessage>(maxPoolSize)
    private val audio = Pools.SynchronizedPool<RtmpMessage>(maxPoolSize)
    private val video = Pools.SynchronizedPool<RtmpMessage>(maxPoolSize)

    // Pools will not be used because caching is performed on the Connection side.
    fun create(value: Byte): RtmpMessage =
        when (value) {
            RtmpMessage.TYPE_CHUNK_SIZE -> RtmpSetChunkSizeMessage()
            RtmpMessage.TYPE_ABORT -> RtmpAbortMessage()
            RtmpMessage.TYPE_ACK -> RtmpAcknowledgementMessage()
            RtmpMessage.TYPE_USER -> user.acquire() ?: RtmpUserControlMessage()
            RtmpMessage.TYPE_WINDOW_ACK -> RtmpWindowAcknowledgementSizeMessage()
            RtmpMessage.TYPE_BANDWIDTH -> RtmpSetPeerBandwidthMessage()
            RtmpMessage.TYPE_AUDIO -> RtmpAudioMessage()
            RtmpMessage.TYPE_VIDEO -> RtmpVideoMessage()
            RtmpMessage.TYPE_AMF0_DATA -> RtmpDataMessage(RtmpObjectEncoding.AMF0)
            RtmpMessage.TYPE_AMF0_COMMAND -> RtmpCommandMessage(RtmpObjectEncoding.AMF0)
            else -> throw IllegalArgumentException("type=$value")
        }

    fun createRtmpSetChunkSizeMessage(): RtmpSetChunkSizeMessage = RtmpSetChunkSizeMessage()

    fun createRtmpUserControlMessage(): RtmpUserControlMessage = (user.acquire() as? RtmpUserControlMessage) ?: RtmpUserControlMessage(user)

    fun createRtmpWindowAcknowledgementSizeMessage(): RtmpWindowAcknowledgementSizeMessage = RtmpWindowAcknowledgementSizeMessage()

    fun createRtmpVideoMessage(): RtmpVideoMessage = (video.acquire() as? RtmpVideoMessage) ?: RtmpVideoMessage(video)

    fun createRtmpAudioMessage(): RtmpAudioMessage = (audio.acquire() as? RtmpAudioMessage) ?: RtmpAudioMessage(audio)
}
