package com.haishinkit.rnnoise

import android.media.AudioFormat
import com.haishinkit.media.audio.AudioProcessor
import com.haishinkit.media.audio.PcmAudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RNNoise v0.2 for 48 kHz PCM16/Float32, mono or stereo (one state per channel).
 *
 * [isBypassed] switches to a delayed dry signal while keeping RNNoise warm for A/B listening.
 * Both paths have a fixed 30 ms latency: 10 ms framing plus RNNoise's 20 ms analysis/lookahead.
 * No VAD gate or automatic gain is added. Stop/detach discards the buffered tail.
 * Disable OS noise suppression on microphone inputs separately, and verify its state.
 */
class RnnoiseAudioProcessor(override val format: PcmAudioFormat) : AudioProcessor {
    @Volatile var isBypassed = false

    @Volatile var processedFrames: Long = 0
        private set

    @Volatile var processingNanos: Long = 0
        private set
    val latencyFrames: Int = 1440

    private var handle: Long
    private var output = ByteBuffer.allocateDirect(0)
    private var staging = ByteBuffer.allocateDirect(0)
    private val bytesPerFrame = format.channels * if (format.encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2

    init {
        require(format.sampleRate == 48_000) { "RNNoise requires 48 kHz input; mixer output may use another rate" }
        System.loadLibrary("haishinkit_rnnoise")
        handle = nativeCreate(format.channels)
        check(handle != 0L) { "Unable to create RNNoise state" }
    }

    @Synchronized override fun process(input: ByteBuffer): ByteBuffer {
        check(handle != 0L) { "RNNoise processor is closed" }
        require(input.remaining() % bytesPerFrame == 0) { "Incomplete input PCM frame" }
        val bytes = input.remaining()
        if (output.capacity() < bytes) output = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val source =
            if (input.isDirect) {
                input
            } else {
                if (staging.capacity() < bytes) staging = ByteBuffer.allocateDirect(bytes)
                staging.clear()
                staging.put(input.duplicate())
                staging.flip()
                staging
            }
        if (bytes == 0) {
            output.clear()
            output.limit(0)
            return output
        }
        val started = System.nanoTime()
        nativeProcess(handle, source, source.position(), output, bytes / bytesPerFrame, format.encoding == AudioFormat.ENCODING_PCM_FLOAT, isBypassed)
        processingNanos += System.nanoTime() - started
        processedFrames += bytes / bytesPerFrame
        output.clear()
        output.limit(bytes)
        return output
    }

    @Synchronized override fun reset() {
        check(handle != 0L)
        nativeDestroy(handle)
        handle = 0
        handle = nativeCreate(format.channels)
        check(handle != 0L)
    }

    @Synchronized override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(channels: Int): Long

    private external fun nativeProcess(
        handle: Long,
        input: ByteBuffer,
        offset: Int,
        output: ByteBuffer,
        frames: Int,
        floating: Boolean,
        bypass: Boolean,
    )

    private external fun nativeDestroy(handle: Long)
}
