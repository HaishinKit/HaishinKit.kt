package com.haishinkit.media.source

import android.util.Size
import android.view.Surface
import com.haishinkit.codec.VideoCodec
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.media.MediaMixer
import java.lang.ref.WeakReference

class VideoCodecSource(
    val videoCodec: VideoCodec,
) : VideoSource {
    private var transform = WeakReference<VideoCodec>(videoCodec)

    override var surface: Surface? = null
        set(value) {
            field = value
            transform.get()?.surface = value
        }
    override var videoSize: Size = Size(0, 0)
    override val imageOrientation: ImageOrientation = ImageOrientation.UP

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun close(): Result<Unit> {
        return Result.success(Unit)
    }
}
