package com.haishinkit.srt

import android.content.Context
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import com.haishinkit.codec.Codec
import com.haishinkit.codec.CodecOption
import com.haishinkit.media.MediaType
import com.haishinkit.media.source.VideoCodecSource
import com.haishinkit.media.source.VideoSource
import com.haishinkit.srt.ts.TSAudioConfig
import com.haishinkit.srt.ts.TSCodec
import com.haishinkit.srt.ts.TSReader
import com.haishinkit.srt.ts.TSSample
import com.haishinkit.srt.ts.TSVideo
import com.haishinkit.srt.ts.TSWriter
import com.haishinkit.stream.Stream
import com.haishinkit.util.MediaFormatUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes or plays a single-program MPEG-TS stream over [connection].
 * Register this stream as a MediaMixer output for capture, or register a StreamView
 * as this stream's output for playback. Codecs/settings and rendering reuse [Stream].
 * Supports H.264/H.265 video and ADTS-framed AAC audio; other codecs are rejected.
 *
 * The connection must be open before [publish] or [play]. One active SrtStream may
 * use a connection at a time. Media jobs belong to the connection's owning scope.
 * [close] stops media processing while keeping the connection open; [dispose]
 * additionally releases codec/rendering resources. Close before reconnecting.
 *
 * @param context Android context for codecs and rendering.
 * @property connection Transport connection, owned and closed separately by the caller.
 */
class SrtStream(context: Context, val connection: SrtConnection) : Stream(context) {
    /** Media-processing state, independent of the connection's handshake state. */
    enum class ReadyState {
        /** No media jobs or active codec processing. */
        IDLE,

        /** Encoding and sending MPEG-TS. */
        PUBLISHING,

        /** Receiving, demultiplexing and decoding MPEG-TS. */
        PLAYING,
    }

    /** Current media-processing state. Async failures return the stream to IDLE. */
    val readyState: StateFlow<ReadyState>
        get() = state.asStateFlow()

    /** Most recent startup/processing error; reset when a new publish/play begins. */
    val failure: StateFlow<Throwable?>
        get() = error.asStateFlow()

    /**
     * Tracks included in the outgoing PMT. Empty derives tracks from the attached mixer.
     * Set before publishing, especially when source formats are not available yet.
     * Reads/assignments use defensive snapshots. Changes while active are rejected.
     */
    var expectedMedias: Set<MediaType>
        get() = synchronized(guard) { expected.toSet() }
        set(value) {
            synchronized(guard) {
                check(current == null)
                expected = value.toSet()
            }
        }

    private val guard = Any()
    private val state = MutableStateFlow(ReadyState.IDLE)
    private val error = MutableStateFlow<Throwable?>(null)
    private var expected = emptySet<MediaType>()

    @Volatile private var current: Run? = null
    private var disposed = false

    /**
     * Starts encoding and publishing. Suspends until codecs have started, then returns.
     * Subsequent errors are exposed through [failure]. Queue overflow terminates the
     * stream instead of dropping arbitrary TS packets or retaining unbounded data.
     *
     * @throws IllegalStateException If disposed, already active, not connected, or the transport is in use.
     * @throws IllegalArgumentException If no tracks or an unsupported encoder codec is selected.
     * @throws Exception If codec startup fails; all partially started resources are stopped.
     */
    suspend fun publish() = start(ReadyState.PUBLISHING)

    /**
     * Starts MPEG-TS reception and codec playback. Returns after the playback pipeline
     * starts; decoders initialize when in-band parameter sets/ADTS configuration arrive.
     * Attach an output view before calling. Subsequent errors are exposed by [failure].
     *
     * @throws IllegalStateException If disposed, active, disconnected, or the transport is in use.
     * @throws Exception If local playback startup fails.
     */
    suspend fun play() = start(ReadyState.PLAYING)

    /**
     * Cancels media jobs while retaining the connection. Cleanup releases codecs and
     * the transport claim before [readyState] returns to [ReadyState.IDLE].
     */
    override fun close() {
        val run = current ?: return
        run.job.cancel()
    }

    /** Cancels media and releases rendering resources after job cleanup. Idempotent. */
    override fun dispose() {
        val job =
            synchronized(guard) {
                if (disposed) return
                disposed = true
                current?.job
            }
        if (job == null) {
            releaseResources()
        } else {
            job.invokeOnCompletion { releaseResources() }
            job.cancel()
        }
    }

    private suspend fun start(target: ReadyState) {
        connection.ownerScope.coroutineContext.ensureActive()
        val run =
            synchronized(guard) {
                check(!disposed && current == null) { "Stream is disposed or already active" }
                val codecs =
                    if (target == ReadyState.PUBLISHING) {
                        val medias =
                            expected.ifEmpty {
                                buildSet {
                                    if (hasAudio) add(MediaType.AUDIO)
                                    if (hasVideo) add(MediaType.VIDEO)
                                }
                            }
                        require(medias.isNotEmpty()) { "Attach mixer sources or set expectedMedias before publishing" }
                        buildSet {
                            if (MediaType.AUDIO in medias) add(TSCodec.AAC)
                            if (MediaType.VIDEO in medias) add(requireNotNull(TSCodec.entries.find { it.mime == videoCodec.profileLevel.mime && it != TSCodec.AAC }) { "Unsupported MPEG-TS video codec" })
                        }
                    } else {
                        emptySet()
                    }
                connection.claimStream(this)
                val created = Run(target, codecs)
                val job = connection.ownerScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) { execute(created) }
                created.job = job
                current = created
                state.value = target
                error.value = null
                job.invokeOnCompletion { cause ->
                    if (!created.started.isCompleted) created.started.completeExceptionally(cause ?: CancellationException("Stream stopped"))
                    finish(created)
                }
                created
            }
        run.job.start()
        try {
            run.started.await()
        } catch (cause: Throwable) {
            run.job.cancel()
            throw cause
        }
    }

    private suspend fun execute(run: Run) {
        try {
            coroutineScope {
                launch {
                    connection.readyState.first { it == SrtConnection.ReadyState.CLOSED }
                    throw IOException("SRT connection closed")
                }
                mode = if (run.target == ReadyState.PUBLISHING) Codec.MODE_ENCODE else Codec.MODE_DECODE
                if (run.target == ReadyState.PUBLISHING) {
                    videoCodec.surface = null
                    audioCodec.inputMimeType = MediaFormat.MIMETYPE_AUDIO_RAW
                    audioCodec.outputMimeType = TSCodec.AAC.mime
                    audioCodec.sampleRate = audioSetting.sampleRate
                    audioCodec.channelCount = audioSetting.channelCount
                    videoCodec.inputMimeType = MediaFormat.MIMETYPE_VIDEO_RAW
                    videoCodec.outputMimeType = videoCodec.profileLevel.mime
                    videoCodec.width = videoSetting.width and -2
                    videoCodec.height = videoSetting.height and -2
                }
                val listener = CodecListener(run)
                audioCodec.listener = listener
                videoCodec.listener = listener
                super@SrtStream.startRunning()
                if (run.target == ReadyState.PUBLISHING) {
                    if (TSCodec.AAC in run.codecs && !audioCodec.isRunning.get()) audioCodec.startRunning()
                    if (run.codecs.any { it != TSCodec.AAC } && !videoCodec.isRunning.get()) videoCodec.startRunning()
                    check(TSCodec.AAC !in run.codecs || audioCodec.isRunning.get()) { "AAC encoder failed to start" }
                    check(run.codecs.none { it != TSCodec.AAC } || videoCodec.isRunning.get()) { "Video encoder failed to start" }
                    run.started.complete(Unit)
                    val writer = TSWriter(run.codecs)
                    for (event in run.encoded) {
                        run.encodedBytes.addAndGet(-event.size.toLong())
                        when (event) {
                            is Encoded.Configuration -> writer.setVideoConfig(event.units)
                            is Encoded.Sample -> connection.sendStream(this@SrtStream, writer.write(event.sample))
                        }
                    }
                } else {
                    launch { decodeAudio(run) }
                    launch { decodeVideo(run) }
                    run.started.complete(Unit)
                    val reader = TSReader()
                    connection.streamInputs(this@SrtStream).collect { data ->
                        for (sample in reader.read(data)) {
                            val size = run.decodeBytes.addAndGet(sample.data.size.toLong())
                            if (size > MAX_QUEUE_BYTES) throw IOException("Decoded media queue is full")
                            (if (sample.codec == TSCodec.AAC) run.audio else run.video).send(sample)
                        }
                    }
                }
            }
        } catch (cause: Throwable) {
            if (cause !is CancellationException && current === run && !run.finishing.get()) error.value = cause
            run.started.completeExceptionally(cause)
        } finally {
            finish(run)
        }
    }

    private suspend fun decodeAudio(run: Run) {
        var configured: TSAudioConfig? = null
        for (sample in run.audio) {
            run.decodeBytes.addAndGet(-sample.data.size.toLong())
            val config = sample.audio ?: continue
            require(config.channels in 1..2) { "Playback supports mono or stereo AAC" }
            if (configured != config) {
                if (configured != null) {
                    audioCodec.stopRunning()
                    drain(run.audioIndices)
                }
                audioCodec.inputMimeType = TSCodec.AAC.mime
                audioCodec.sampleRate = config.sampleRate
                audioCodec.channelCount = config.channels
                audioCodec.options = audioCodec.options.filter { it.key != "csd-0" } + CodecOption("csd-0", ByteBuffer.wrap(config.csd()))
                audioCodec.startRunning()
                check(audioCodec.isRunning.get()) { "AAC decoder failed to start" }
                configured = config
                hasAudio = true
            }
            feed(audioCodec.codec!!, run.audioIndices.receive(), sample)
        }
    }

    private suspend fun decodeVideo(run: Run) {
        var configuration = emptyList<ByteArray>()
        var configuredCodec: TSCodec? = null
        var appliedConfiguration = emptyList<ByteArray>()
        for (sample in run.video) {
            run.decodeBytes.addAndGet(-sample.data.size.toLong())
            val updated = TSVideo.configuration(sample.data, sample.codec, sample.framing)
            val merged = (configuration.filter { old -> updated.none { TSVideo.type(it, sample.codec) == TSVideo.type(old, sample.codec) } } + updated)
            val changed = configuredCodec != sample.codec || !sameBytes(appliedConfiguration, merged)
            configuration = merged
            if (!TSVideo.hasConfiguration(configuration, sample.codec)) continue
            if (changed && !sample.keyframe) continue
            if (changed) {
                if (configuredCodec != null) {
                    videoCodec.stopRunning()
                    drain(run.videoIndices)
                }
                run.videoKeyframes.clear()
                val spsType = if (sample.codec == TSCodec.AVC) 7 else 33
                val dimensions = TSVideo.dimensions(configuration.first { TSVideo.type(it, sample.codec) == spsType }, sample.codec)
                videoCodec.inputMimeType = sample.codec.mime
                videoCodec.width = dimensions.first
                videoCodec.height = dimensions.second
                val csd =
                    if (sample.codec == TSCodec.AVC) {
                        listOf(
                            CodecOption("csd-0", ByteBuffer.wrap(TSVideo.annexB(configuration.filter { TSVideo.type(it, sample.codec) == 7 }))),
                            CodecOption("csd-1", ByteBuffer.wrap(TSVideo.annexB(configuration.filter { TSVideo.type(it, sample.codec) == 8 }))),
                        )
                    } else {
                        listOf(CodecOption("csd-0", ByteBuffer.wrap(TSVideo.annexB(configuration))))
                    }
                videoCodec.options = videoCodec.options.filter { it.key != "csd-0" && it.key != "csd-1" } + csd
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
                                if (current === run && !run.finishing.get()) {
                                    videoCodec.surface = value
                                    if (value != null) attached.complete(Unit)
                                }
                            }
                    }
                screen.attachVideo(0, source)
                withTimeoutOrNull(5000) { attached.await() } ?: throw IOException("Decoder output surface was not attached")
                videoCodec.startRunning()
                check(videoCodec.isRunning.get()) { "Video decoder failed to start" }
                hasVideo = true
                configuredCodec = sample.codec
                appliedConfiguration = configuration
            }
            val index = run.videoIndices.receive()
            if (sample.keyframe) {
                run.videoKeyframes.add(sample.presentationTimeUs)
                while (run.videoKeyframes.size > 64) run.videoKeyframes.pollFirst()
            }
            feed(videoCodec.codec!!, index, sample)
        }
    }

    private fun feed(
        codec: MediaCodec,
        index: Int,
        sample: TSSample,
    ) {
        val input = checkNotNull(codec.getInputBuffer(index))
        input.clear()
        require(sample.data.size <= input.remaining()) { "Encoded access unit exceeds decoder input capacity" }
        input.put(sample.data)
        codec.queueInputBuffer(index, 0, sample.data.size, sample.presentationTimeUs, if (sample.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
    }

    private fun finish(run: Run) {
        synchronized(guard) {
            if (current !== run || !run.finishing.compareAndSet(false, true)) return
            run.encoded.cancel()
            run.audio.cancel()
            run.video.cancel()
            run.audioIndices.cancel()
            run.videoIndices.cancel()
            run.started.completeExceptionally(CancellationException("Stream closed"))
            try {
                super.stopRunning()
                audioCodec.stopRunning()
                videoCodec.stopRunning()
                if (run.target == ReadyState.PLAYING) mediaLink.audioTrack = null
            } finally {
                audioCodec.listener = null
                videoCodec.listener = null
                audioCodec.options = audioSetting.options
                videoCodec.options = videoSetting.options
                connection.releaseStream(this)
                current = null
                state.value = ReadyState.IDLE
            }
        }
    }

    private fun releaseResources() {
        synchronized(guard) { super.dispose() }
    }

    private fun fail(
        run: Run,
        cause: Throwable,
    ) {
        if (current !== run || run.finishing.get()) return
        error.value = cause
        run.job.cancel(CancellationException("Media processing failed", cause))
    }

    private fun enqueue(
        run: Run,
        event: Encoded,
    ) {
        if (run.encodedBytes.addAndGet(event.size.toLong()) > MAX_QUEUE_BYTES || run.encoded.trySend(event).isFailure) {
            run.encodedBytes.addAndGet(-event.size.toLong())
            fail(run, IOException("Encoded media queue is full"))
        }
    }

    private fun drain(channel: Channel<Int>) {
        while (channel.tryReceive().isSuccess) { }
    }

    private fun sameBytes(
        first: List<ByteArray>,
        second: List<ByteArray>,
    ): Boolean = first.size == second.size && first.indices.all { first[it].contentEquals(second[it]) }

    private inner class CodecListener(val run: Run) : Codec.Listener {
        override fun onInputBufferAvailable(
            mime: String,
            codec: MediaCodec,
            index: Int,
        ) {
            if (current !== run || run.finishing.get()) return
            val owner = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) audioCodec else videoCodec
            owner.withCurrentCodec(codec) {
                if (current !== run || run.finishing.get()) return@withCurrentCodec
                val indices = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) run.audioIndices else run.videoIndices
                if (indices.trySend(index).isFailure) fail(run, IOException("Decoder input queue is full"))
            }
        }

        override fun onError(
            mime: String,
            cause: MediaCodec.CodecException,
        ) {
            fail(run, cause)
        }

        override fun onFormatChanged(
            mime: String,
            mediaFormat: MediaFormat,
        ) {
            if (current !== run || run.finishing.get()) return
            try {
                if (run.target == ReadyState.PUBLISHING) {
                    if (mime.startsWith("video/")) {
                        val config = (0..2).flatMap { index -> mediaFormat.getByteBuffer("csd-$index")?.let { TSVideo.units(copy(it), TSVideo.Framing.ANNEX_B) } ?: emptyList() }
                        enqueue(run, Encoded.Configuration(config))
                    }
                } else if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                    mediaLink.audioTrack = MediaFormatUtil.createAudioTrack(mediaFormat)
                } else if (mime == MediaFormat.MIMETYPE_VIDEO_RAW) {
                    val size = Size(MediaFormatUtil.getWidth(mediaFormat), MediaFormatUtil.getHeight(mediaFormat))
                    screen.frame = Rect(0, 0, size.width, size.height)
                    video.videoSize = size
                }
            } catch (cause: Exception) {
                fail(run, cause)
            }
        }

        override fun onSampleOutput(
            mime: String,
            index: Int,
            info: MediaCodec.BufferInfo,
            buffer: ByteBuffer,
        ): Boolean {
            if (current !== run || run.finishing.get()) return true
            if (run.target == ReadyState.PLAYING) {
                queueOutputBuffer(
                    if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) MediaType.AUDIO else MediaType.VIDEO,
                    index,
                    buffer,
                    info.presentationTimeUs,
                    (mime == MediaFormat.MIMETYPE_VIDEO_RAW && run.videoKeyframes.remove(info.presentationTimeUs)) || info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                )
                return false
            }
            if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return true
            try {
                val view = buffer.duplicate()
                view.position(info.offset)
                view.limit(info.offset + info.size)
                val codec = requireNotNull(TSCodec.entries.firstOrNull { it.mime == mime }) { "Unsupported TS encoder codec" }
                if (codec !in run.codecs) return true
                val audio = if (codec == TSCodec.AAC) TSAudioConfig(audioCodec.sampleRate, audioCodec.channelCount, audioCodec.aacProfile) else null
                enqueue(run, Encoded.Sample(TSSample(codec, copy(view), info.presentationTimeUs, keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, audio = audio, framing = TSVideo.Framing.ANNEX_B)))
            } catch (cause: Exception) {
                fail(run, cause)
            }
            return true
        }
    }

    private sealed class Encoded(val size: Int) {
        class Configuration(val units: List<ByteArray>) : Encoded(units.sumOf { it.size })

        class Sample(val sample: TSSample) : Encoded(sample.data.size)
    }

    private class Run(val target: ReadyState, val codecs: Set<TSCodec>) {
        val started = CompletableDeferred<Unit>()
        val finishing = AtomicBoolean()
        val encoded = Channel<Encoded>(32)
        val audio = Channel<TSSample>(32)
        val video = Channel<TSSample>(32)
        val audioIndices = Channel<Int>(32)
        val videoIndices = Channel<Int>(32)
        val encodedBytes = AtomicLong()
        val decodeBytes = AtomicLong()
        val videoKeyframes = ConcurrentSkipListSet<Long>()
        lateinit var job: Job
    }

    private companion object {
        const val MAX_QUEUE_BYTES = 8L * 1024 * 1024

        fun copy(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    }
}
