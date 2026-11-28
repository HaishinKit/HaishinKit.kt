package com.haishinkit.media.source

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.haishinkit.media.audio.PcmAudioFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Decoder outputs are copied once into the native mixing ring before release. */
internal class AssetAudioDecoder(
    private val context: Context,
    private val uri: Uri,
    private val clock: AssetPlaybackClock,
    private var predecessor: AssetAudioDecoder?,
    private val pcm: (ByteBuffer, PcmAudioFormat) -> Unit,
    private val discontinuity: () -> Unit,
    private val error: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(true)
    private val worker = Thread(::decode, "HaishinKit.AssetAudio").apply { isDaemon = true }

    fun start() = worker.start()

    fun cancel() {
        running.set(false)
        worker.interrupt()
    }

    fun awaitRelease() {
        var previous: AssetAudioDecoder? = this
        while (previous != null) {
            if (Thread.currentThread() !== previous.worker) previous.worker.join()
            previous = previous.predecessor
        }
    }

    private fun decode() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            predecessor?.awaitRelease()
            predecessor = null
            if (!running.get() || !clock.active) return
            val source = MediaExtractor().also { extractor = it }
            requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r")).use { descriptor ->
                if (descriptor.declaredLength < 0) {
                    source.setDataSource(descriptor.fileDescriptor)
                } else {
                    source.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            val track = (0 until source.trackCount).firstOrNull { source.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
            source.selectTrack(track)
            val encoded = source.getTrackFormat(track)
            encoded.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            if (Build.VERSION.SDK_INT >= 32) {
                encoded.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 2)
            } else if (encoded.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
                encoded.setInteger("aac-max-output-channel_count", 2)
            }
            val decoder = MediaCodec.createDecoderByType(checkNotNull(encoded.getString(MediaFormat.KEY_MIME))).also { codec = it }
            decoder.configure(encoded, null, null, 0)
            decoder.start()
            var format: PcmAudioFormat? = null
            var pass: AssetPlaybackClock.Pass? = null
            var inputEnded = false
            var outputEnded = false
            var joinPositionUs = 0L
            var resynchronizing = false
            val info = MediaCodec.BufferInfo()
            while (running.get() && clock.active) {
                val current = clock.pass
                if (current == null || (outputEnded && current === pass)) {
                    LockSupport.parkNanos(5_000_000)
                    continue
                }
                if (current !== pass) {
                    if (pass != null) decoder.flush()
                    joinPositionUs = current.positionUs().coerceAtLeast(0)
                    source.seekTo(joinPositionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    pass = current
                    inputEnded = false
                    outputEnded = false
                    resynchronizing = false
                    discontinuity()
                }
                if (!inputEnded) {
                    // A full input queue must not delay draining already-decoded PCM.
                    val index = decoder.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val input = checkNotNull(decoder.getInputBuffer(index))
                        input.clear()
                        val count = source.readSampleData(input, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            require(source.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted asset audio is not supported" }
                            val flags = if (source.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0
                            decoder.queueInputBuffer(index, 0, count, source.sampleTime, flags)
                            source.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        format = pcmFormat(output)
                    }
                    else ->
                        if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val output = checkNotNull(decoder.getOutputBuffer(index))
                                    val actual = format ?: pcmFormat(decoder.outputFormat).also { format = it }
                                    val frameBytes = actual.channels * (if (actual.encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2)
                                    val deadline = current.deadline(info.presentationTimeUs)
                                    val lateUs = (joinPositionUs - info.presentationTimeUs).coerceAtLeast(0)
                                    val frames = info.size / frameBytes
                                    val durationUs = (frames * 1_000_000L + actual.sampleRate - 1) / actual.sampleRate
                                    val lateFrames = if (lateUs >= durationUs) frames else (lateUs * actual.sampleRate / 1_000_000).toInt()
                                    if (lateFrames < frames) {
                                        val target = deadline + lateFrames * 1_000_000_000L / actual.sampleRate
                                        waitUntil(target, current)
                                        if (running.get() && clock.active && clock.pass === current) {
                                            val expired = current.expiredFrames(info.presentationTimeUs, frames, actual.sampleRate, System.nanoTime(), resynchronizing)
                                            if (expired > 0) {
                                                if (!resynchronizing) discontinuity()
                                                resynchronizing = true
                                            }
                                            val trim = maxOf(lateFrames, expired) * frameBytes
                                            if (trim < info.size) {
                                                output.limit(info.offset + info.size)
                                                output.position(info.offset + trim)
                                                pcm(output, actual)
                                                resynchronizing = false
                                            }
                                        }
                                    }
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally {
                                decoder.releaseOutputBuffer(index, false)
                            }
                        }
                }
            }
        } catch (cause: Exception) {
            if (running.get() && clock.active) error(cause)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            runCatching { extractor?.release() }
            running.set(false)
        }
    }

    private fun pcmFormat(format: MediaFormat): PcmAudioFormat =
        PcmAudioFormat(
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT,
        )

    private fun waitUntil(
        deadline: Long,
        pass: AssetPlaybackClock.Pass,
    ) {
        while (running.get() && clock.active && clock.pass === pass) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            LockSupport.parkNanos(minOf(remaining, 10_000_000))
        }
    }
}
