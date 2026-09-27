package com.haishinkit.media

import java.nio.ByteBuffer

/**
 * A media payload and its capture or codec metadata.
 *
 * @property type Whether the buffer contains audio or video.
 * @property index The source track identifier for captured audio, or the codec output buffer index for playback.
 * @property payload The media bytes, or `null` for surface-backed video. Producers may reuse this storage.
 * @property timestamp The presentation timestamp in microseconds for decoded playback; capture sources may use zero.
 * @property sync Whether this is a synchronization frame, such as a video keyframe.
 */
data class MediaBuffer(
    val type: MediaType,
    var index: Int,
    var payload: ByteBuffer? = null,
    var timestamp: Long = 0L,
    var sync: Boolean = false,
)
