package com.haishinkit.rtc

import android.content.Context
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Size
import android.view.Surface
import com.haishinkit.codec.Codec
import com.haishinkit.codec.CodecOption
import com.haishinkit.codec.VideoCodecProfileLevel
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaType
import com.haishinkit.media.source.VideoCodecSource
import com.haishinkit.media.source.VideoSource
import com.haishinkit.stream.Stream
import com.haishinkit.util.MediaFormatUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong

/** H.264/Opus media stream owned by [RTCSession]. Uses the existing mixer and output views. */
class RTCStream internal constructor(context: Context, private val stop: () -> Unit) : Stream(context) {
    /** Tracks offered for WHIP. Set before connecting; an empty set derives them from the mixer. */
    var expectedMedias: Set<MediaType> = emptySet()

    @Volatile private var current: Run? = null

    @Volatile private var disposed = false
    private val ownedScreen = screen
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        audioSetting.sampleRate = 48_000
        videoSetting.profileLevel = VideoCodecProfileLevel.H264_BASELINE_3_1
    }

    override fun close() = stop()

    override fun dispose() {
        disposed = true
        stop()
        if (current == null) releaseResources()
    }

    override fun append(buffer: MediaBuffer) {
        // Playback is output only. Never feed remote audio to an encoder/decoder input.
        if (current?.publishing == true) super.append(buffer)
    }

    internal fun offeredMedias(): Set<MediaType> {
        check(!disposed) { "Stream is disposed" }
        return expectedMedias.toSet().ifEmpty {
            buildSet {
                if (hasAudio) add(MediaType.AUDIO)
                if (hasVideo) add(MediaType.VIDEO)
            }
        }.also { require(it.isNotEmpty()) { "Attach mixer sources or set expectedMedias before publishing" } }
    }

    internal fun receive(
        audio: Boolean,
        data: ByteArray,
        timestamp: Long,
    ) {
        val run = current ?: return
        if (run.publishing || (if (audio) "audio" else "video") !in run.tracks) return
        val pts =
            synchronized(run) {
                (if (audio) run.audioClock else run.videoClock).timeUs(timestamp, System.nanoTime() / 1000 - run.epochUs)
            }
        enqueue(run, if (audio) run.audio else run.video, Sample(audio, data, pts))
    }

    internal fun requestKeyframe() {
        current?.keyframe?.trySend(Unit)
    }

    internal fun requestBitrate(bitrate: Int) {
        current?.bitrate?.trySend(bitrate)
    }

    internal suspend fun execute(
        publishing: Boolean,
        tracks: Set<String>,
        peer: NativePeer,
        videoConfiguration: List<ByteArray>,
        ready: () -> Unit,
    ): Unit =
        coroutineScope {
            check(current == null && !disposed)
            val run = Run(publishing, tracks, videoConfiguration)
            current = run
            try {
                mode = if (publishing) Codec.MODE_ENCODE else Codec.MODE_DECODE
                audioCodec.inputMimeType = if (publishing) MediaFormat.MIMETYPE_AUDIO_RAW else MediaFormat.MIMETYPE_AUDIO_OPUS
                audioCodec.outputMimeType = if (publishing) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_RAW
                videoCodec.inputMimeType = if (publishing) MediaFormat.MIMETYPE_VIDEO_RAW else MediaFormat.MIMETYPE_VIDEO_AVC
                videoCodec.outputMimeType = if (publishing) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_RAW
                val listener = Listener(run)
                audioCodec.listener = listener
                videoCodec.listener = listener
                launch { throw run.failed.await() }
                if (publishing) {
                    require(audioSetting.sampleRate in setOf(8000, 12000, 16000, 24000, 48000)) { "Opus requires an 8/12/16/24/48 kHz PCM source; use 48 kHz capture" }
                    require(audioSetting.channelCount in 1..2)
                    require(videoSetting.profileLevel == VideoCodecProfileLevel.H264_BASELINE_3_1) { "RTC currently offers H.264 Baseline level 3.1" }
                    audioCodec.sampleRate = audioSetting.sampleRate
                    audioCodec.channelCount = audioSetting.channelCount
                    videoCodec.surface = null
                    // Start only tracks accepted by the server. Base startRunning() would
                    // start every attached mixer source, including rejected media.
                    if ("audio" in tracks) audioCodec.startRunning()
                    if ("video" in tracks) videoCodec.startRunning()
                    check("audio" !in tracks || audioCodec.isRunning.get()) { "Opus encoder failed to start" }
                    check("video" !in tracks || videoCodec.isRunning.get()) { "H.264 encoder failed to start" }
                    isRunning.set(true)
                    launch {
                        for (sample in run.encoded) {
                            run.bytes.addAndGet(-sample.data.size.toLong())
                            peer.send(sample.audio, sample.data, sample.timeUs)
                        }
                    }
                    launch {
                        for (ignored in run.keyframe) videoCodec.codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    }
                    launch {
                        for (rate in run.bitrate) {
                            if (rate > 0 && "video" in tracks) videoCodec.bitRate = minOf(rate, videoSetting.bitRate)
                        }
                    }
                } else {
                    hasAudio = "audio" in tracks
                    // SDP can retain a video section without sending video. Start audio
                    // immediately; switch to video synchronization once a frame is decodable.
                    hasVideo = false
                    super@RTCStream.startRunning()
                    if ("audio" in tracks) launch { decodeAudio(run) }
                    if ("video" in tracks) {
                        launch { decodeVideo(run, peer) }
                        launch {
                            while (true) {
                                peer.requestKeyframe()
                                delay(2000)
                                if (run.hasVideoKeyframe) break
                            }
                        }
                    }
                }
                ready()
                awaitCancellation()
            } finally {
                val children = currentCoroutineContext()[Job]!!.children.toList()
                current = null
                withContext(NonCancellable) {
                    children.forEach { it.cancel() }
                    children.joinAll()
                    // Wake decoder consumers before stopping codecs; callbacks can no longer
                    // enqueue buffers belonging to this generation.
                    run.audio.cancel()
                    run.video.cancel()
                    run.encoded.cancel()
                    run.audioIndices.cancel()
                    run.videoIndices.cancel()
                    run.keyframe.cancel()
                    run.bitrate.cancel()
                    super@RTCStream.stopRunning()
                    audioCodec.stopRunning()
                    videoCodec.stopRunning()
                    audioCodec.listener = null
                    videoCodec.listener = null
                    audioCodec.options = audioSetting.options
                    videoCodec.options = videoSetting.options
                    if (!publishing) mediaLink.audioTrack = null
                    if (disposed) releaseResources()
                }
            }
        }

    private fun releaseResources() {
        if (released.compareAndSet(false, true)) {
            super.dispose()
            ownedScreen.dispose()
        }
    }

    private suspend fun decodeAudio(run: Run) {
        audioCodec.sampleRate = 48_000
        audioCodec.channelCount = 2
        val header =
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII))
                put(1)
                put(2)
                putShort(0)
                putInt(48_000)
                putShort(0)
                put(0)
            }.array()
        audioCodec.options = audioSetting.options.filterNot { it.key.startsWith("csd-") } +
            listOf(
                CodecOption("csd-0", ByteBuffer.wrap(header)),
                CodecOption("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() }),
                CodecOption("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).apply { flip() }),
            )
        audioCodec.startRunning()
        check(audioCodec.isRunning.get()) { "Opus decoder failed to start" }
        var lastTime = Long.MIN_VALUE
        for (sample in run.audio) {
            run.bytes.addAndGet(-sample.data.size.toLong())
            if (sample.timeUs <= lastTime) continue
            lastTime = sample.timeUs
            feed(audioCodec.codec!!, run.audioIndices.receive(), sample)
        }
    }

    private suspend fun decodeVideo(
        run: Run,
        peer: NativePeer,
    ) {
        var sps: ByteArray? = run.videoConfiguration.firstOrNull { it[0].toInt() and 31 == 7 }
        var pps: ByteArray? = run.videoConfiguration.firstOrNull { it[0].toInt() and 31 == 8 }
        var appliedSps: ByteArray? = null
        var appliedPps: ByteArray? = null
        var lastTime = Long.MIN_VALUE
        for (sample in run.video) {
            run.bytes.addAndGet(-sample.data.size.toLong())
            if (sample.timeUs <= lastTime) continue
            val units = H264.units(sample.data)
            units.firstOrNull { it[0].toInt() and 31 == 7 }?.let { sps = it }
            units.firstOrNull { it[0].toInt() and 31 == 8 }?.let { pps = it }
            val key = units.any { it[0].toInt() and 31 == 5 }
            val currentSps = sps ?: continue
            val currentPps = pps ?: continue
            val changed = !currentSps.contentEquals(appliedSps) || !currentPps.contentEquals(appliedPps)
            if (changed && !key) {
                peer.requestKeyframe()
                continue
            }
            if (changed) {
                videoCodec.stopRunning()
                while (run.videoIndices.tryReceive().isSuccess) { }
                val dimensions = H264.dimensions(currentSps)
                videoCodec.width = dimensions.first
                videoCodec.height = dimensions.second
                videoCodec.options = videoSetting.options.filterNot { it.key.startsWith("csd-") } +
                    listOf(
                        CodecOption("csd-0", ByteBuffer.wrap(H264.annexB(listOf(currentSps)))),
                        CodecOption("csd-1", ByteBuffer.wrap(H264.annexB(listOf(currentPps)))),
                    )
                val size = Size(dimensions.first, dimensions.second)
                screen.frame = Rect(0, 0, size.width, size.height)
                video.videoSize = size
                videoCodec.surface = null
                val attached = CompletableDeferred<Unit>()
                val delegate = VideoCodecSource(videoCodec).apply { videoSize = size }
                val source =
                    object : VideoSource by delegate {
                        override var surface: Surface? = null
                            set(value) {
                                field = value
                                if (current === run) {
                                    videoCodec.surface = value
                                    if (value != null) attached.complete(Unit)
                                }
                            }
                    }
                screen.attachVideo(0, source)
                withTimeout(5000) { attached.await() }
                videoCodec.startRunning()
                check(videoCodec.isRunning.get()) { "H.264 decoder failed to start" }
                hasVideo = true
                appliedSps = currentSps
                appliedPps = currentPps
            }
            if (key) {
                run.hasVideoKeyframe = true
                run.keyframes.add(sample.timeUs)
                while (run.keyframes.size > 64) run.keyframes.pollFirst()
            }
            lastTime = sample.timeUs
            feed(videoCodec.codec!!, run.videoIndices.receive(), sample)
        }
    }

    private fun feed(
        codec: MediaCodec,
        index: Int,
        sample: Sample,
    ) {
        val input = checkNotNull(codec.getInputBuffer(index))
        input.clear()
        require(sample.data.size <= input.remaining()) { "Access unit exceeds decoder buffer capacity" }
        input.put(sample.data)
        codec.queueInputBuffer(index, 0, sample.data.size, sample.timeUs, 0)
    }

    private fun enqueue(
        run: Run,
        channel: Channel<Sample>,
        sample: Sample,
    ) {
        if (sample.data.size > MAX_FRAME || run.bytes.addAndGet(sample.data.size.toLong()) > MAX_QUEUE || channel.trySend(sample).isFailure) {
            run.failed.complete(IOException("RTC media queue capacity exceeded"))
        }
    }

    private inner class Listener(private val run: Run) : Codec.Listener {
        private var configuration = emptyList<ByteArray>()

        override fun onInputBufferAvailable(
            mime: String,
            codec: MediaCodec,
            index: Int,
        ) {
            if (current !== run) return
            val indices = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) run.audioIndices else run.videoIndices
            if (indices.trySend(index).isFailure) run.failed.complete(IOException("Decoder input queue capacity exceeded"))
        }

        override fun onError(
            mime: String,
            cause: MediaCodec.CodecException,
        ) {
            if (current === run) run.failed.complete(cause)
        }

        override fun onFormatChanged(
            mime: String,
            mediaFormat: MediaFormat,
        ) {
            if (current !== run) return
            try {
                if (run.publishing && mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    configuration = (0..1).flatMap { mediaFormat.getByteBuffer("csd-$it")?.let { data -> H264.units(copy(data)) } ?: emptyList() }
                } else if (!run.publishing && mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                    mediaLink.audioTrack = MediaFormatUtil.createAudioTrack(mediaFormat)
                } else if (!run.publishing && mime == MediaFormat.MIMETYPE_VIDEO_RAW) {
                    val size = Size(MediaFormatUtil.getWidth(mediaFormat), MediaFormatUtil.getHeight(mediaFormat))
                    screen.frame = Rect(0, 0, size.width, size.height)
                    video.videoSize = size
                }
            } catch (cause: Exception) {
                run.failed.complete(cause)
            }
        }

        override fun onSampleOutput(
            mime: String,
            index: Int,
            info: MediaCodec.BufferInfo,
            buffer: ByteBuffer,
        ): Boolean {
            if (current !== run) return true
            if (!run.publishing) {
                queueOutputBuffer(if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) MediaType.AUDIO else MediaType.VIDEO, index, buffer, info.presentationTimeUs, run.keyframes.remove(info.presentationTimeUs))
                return false
            }
            if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return true
            try {
                val audio = mime == MediaFormat.MIMETYPE_AUDIO_OPUS
                var data =
                    copy(
                        buffer.duplicate().apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                        },
                    )
                if (!audio && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                    data = H264.annexB(configuration + H264.units(data))
                }
                enqueue(run, run.encoded, Sample(audio, data, info.presentationTimeUs))
            } catch (cause: Exception) {
                run.failed.complete(cause)
            }
            return true
        }
    }

    private data class Sample(val audio: Boolean, val data: ByteArray, val timeUs: Long)

    private class Run(val publishing: Boolean, val tracks: Set<String>, val videoConfiguration: List<ByteArray>) {
        val epochUs = System.nanoTime() / 1000
        val audioClock = RTPClock(48_000)
        val videoClock = RTPClock(90_000)
        val failed = CompletableDeferred<Throwable>()
        val encoded = Channel<Sample>(64)
        val audio = Channel<Sample>(64)
        val video = Channel<Sample>(64)
        val audioIndices = Channel<Int>(32)
        val videoIndices = Channel<Int>(32)
        val keyframe = Channel<Unit>(Channel.CONFLATED)
        val bitrate = Channel<Int>(Channel.CONFLATED)
        val bytes = AtomicLong()
        val keyframes = ConcurrentSkipListSet<Long>()

        @Volatile var hasVideoKeyframe = false
    }

    private companion object {
        const val MAX_FRAME = 4 * 1024 * 1024
        const val MAX_QUEUE = 8 * 1024 * 1024

        fun copy(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    }
}
