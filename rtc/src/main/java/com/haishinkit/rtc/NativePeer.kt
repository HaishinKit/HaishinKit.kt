package com.haishinkit.rtc

import java.io.Closeable

/** JNI boundary. Callbacks only enqueue events; they never block native transport threads. */
internal class NativePeer(
    publishing: Boolean,
    audio: Boolean,
    video: Boolean,
    iceServers: Array<String>,
    private val event: (Int, Int, String) -> Unit,
    private val frame: (Boolean, ByteArray, Long) -> Unit,
) : Closeable {
    private var handle = create(publishing, audio, video, iceServers)

    @Synchronized fun offer() = localDescription(handle, "offer")

    @Synchronized fun answer(sdp: String) = remoteDescription(handle, sdp, "answer")

    @Synchronized fun acceptOffer(sdp: String) {
        remoteDescription(handle, sdp, "offer")
        localDescription(handle, "answer")
    }

    @Synchronized fun send(
        audio: Boolean,
        data: ByteArray,
        timeUs: Long,
    ) = sendFrame(handle, audio, data, timeUs)

    @Synchronized fun requestKeyframe() = keyframe(handle)

    @Synchronized override fun close() {
        if (handle != 0L) {
            val previous = handle
            handle = 0
            destroy(previous)
        }
    }

    @Suppress("unused")
    private fun onEvent(
        kind: Int,
        value: Int,
        text: String,
    ) = event(kind, value, text)

    @Suppress("unused")
    private fun onFrame(
        audio: Boolean,
        data: ByteArray,
        timestamp: Long,
    ) = frame(audio, data, timestamp)

    private external fun create(
        publishing: Boolean,
        audio: Boolean,
        video: Boolean,
        servers: Array<String>,
    ): Long

    private external fun localDescription(
        handle: Long,
        type: String,
    )

    private external fun remoteDescription(
        handle: Long,
        sdp: String,
        type: String,
    )

    private external fun sendFrame(
        handle: Long,
        audio: Boolean,
        data: ByteArray,
        timeUs: Long,
    )

    private external fun keyframe(handle: Long)

    private external fun destroy(handle: Long)

    companion object {
        const val STATE = 0
        const val SDP = 1
        const val ERROR = 2
        const val KEYFRAME = 3
        const val BITRATE = 4
        const val TRACK_OPEN = 5

        init {
            System.loadLibrary("haishinkit_rtc")
        }
    }
}
