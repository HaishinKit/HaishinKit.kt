package com.haishinkit.rtmp

import android.view.Surface
import com.haishinkit.media.source.VideoCodecSource
import com.haishinkit.media.source.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Waits for the renderer to install the first decoder output surface. */
internal class RtmpPlaybackVideoSource(
    private val source: VideoCodecSource,
) : VideoSource by source {
    override var surface: Surface?
        get() = source.surface
        set(value) {
            source.surface = value
            if (value != null) surfaceReady.countDown()
        }

    private val surfaceReady = CountDownLatch(1)

    fun awaitSurface(): Boolean =
        try {
            surfaceReady.await(5, TimeUnit.SECONDS) && surface != null
        } catch (cause: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}
