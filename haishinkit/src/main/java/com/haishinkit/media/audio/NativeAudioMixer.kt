package com.haishinkit.media.audio

import com.haishinkit.media.AudioMixerSettings
import com.haishinkit.media.AudioMixerTrackSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** First input/format changes allocate native rings; steady append/render reuse their storage. */
internal class NativeAudioMixer(private val settings: AudioMixerSettings) : AutoCloseable {
    val frames = settings.sampleRate / 100
    val output: ByteBuffer = ByteBuffer.allocateDirect(frames * settings.channels * 2).order(ByteOrder.nativeOrder())
    val droppedFrames: Long @Synchronized get() = nativeDroppedFrames(handle)
    private var handle = nativeCreate(settings.sampleRate, settings.channels, frames)
    private var staging = ByteBuffer.allocateDirect(0)

    @Synchronized fun add(track: Int) = nativeAdd(handle, track)

    @Synchronized fun remove(track: Int) = nativeRemove(handle, track)

    @Synchronized fun clear(track: Int) {
        if (handle != 0L) nativeClear(handle, track)
    }

    @Synchronized fun configure(
        track: Int,
        value: AudioMixerTrackSettings,
    ) = nativeSettings(handle, track, value.volume, value.isMuted)

    @Synchronized fun append(
        track: Int,
        buffer: ByteBuffer,
        format: PcmAudioFormat,
    ) {
        if (handle == 0L) return
        val input =
            if (buffer.isDirect) {
                buffer
            } else {
                if (staging.capacity() < buffer.remaining()) staging = ByteBuffer.allocateDirect(buffer.remaining())
                staging.clear()
                val position = buffer.position()
                try {
                    staging.put(buffer)
                } finally {
                    buffer.position(position)
                }
                staging.flip()
                staging
            }
        nativeAppend(handle, track, input, input.position(), input.remaining(), format.sampleRate, format.channels, format.encoding)
    }

    @Synchronized fun render(muted: Boolean): ByteBuffer {
        output.clear()
        nativeRender(handle, output, output.capacity(), muted)
        return output
    }

    @Synchronized override fun close() {
        if (handle == 0L) return
        nativeClose(handle)
        handle = 0
    }

    private external fun nativeCreate(
        rate: Int,
        channels: Int,
        frames: Int,
    ): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeAdd(
        handle: Long,
        track: Int,
    )

    private external fun nativeRemove(
        handle: Long,
        track: Int,
    )

    private external fun nativeClear(
        handle: Long,
        track: Int,
    )

    private external fun nativeSettings(
        handle: Long,
        track: Int,
        gain: Float,
        muted: Boolean,
    )

    private external fun nativeAppend(
        handle: Long,
        track: Int,
        input: ByteBuffer,
        offset: Int,
        bytes: Int,
        rate: Int,
        channels: Int,
        encoding: Int,
    )

    private external fun nativeRender(
        handle: Long,
        output: ByteBuffer,
        bytes: Int,
        muted: Boolean,
    )

    private external fun nativeDroppedFrames(handle: Long): Long

    companion object {
        init {
            System.loadLibrary("haishinkit_audio")
        }
    }
}
