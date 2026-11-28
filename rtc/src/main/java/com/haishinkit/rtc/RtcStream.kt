package com.haishinkit.rtc

import android.content.Context
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** H.264/VP8/VP9 and Opus media stream owned by [RtcSession]. Uses the existing mixer and output views. */
class RtcStream internal constructor(context: Context, private val stop: () -> Unit) : Stream(context) {
    /** Tracks offered for WHIP. Set before connecting; an empty set derives them from the mixer. */
    var expectedMedias: Set<MediaType> = emptySet()

    internal var audioUsage = AudioAttributes.USAGE_MEDIA

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
        videoType: RtcVideoCodec = RtcVideoCodec.H264,
        videoLimits: VpLimits? = null,
        ready: () -> Unit,
    ): Unit =
        coroutineScope {
            check(current == null && !disposed)
            val run = Run(publishing, tracks, videoConfiguration, videoType, videoLimits)
            current = run
            try {
                mode = if (publishing) Codec.MODE_ENCODE else Codec.MODE_DECODE
                audioCodec.inputMimeType = if (publishing) MediaFormat.MIMETYPE_AUDIO_RAW else MediaFormat.MIMETYPE_AUDIO_OPUS
                audioCodec.outputMimeType = if (publishing) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_RAW
                videoCodec.inputMimeType = if (publishing) MediaFormat.MIMETYPE_VIDEO_RAW else videoType.mime
                videoCodec.outputMimeType = if (publishing) videoType.mime else MediaFormat.MIMETYPE_VIDEO_RAW
                val listener = Listener(run)
                audioCodec.listener = listener
                videoCodec.listener = listener
                launch { throw run.failed.await() }
                if (publishing) {
                    require(audioSetting.sampleRate in setOf(8000, 12000, 16000, 24000, 48000)) { "Opus requires an 8/12/16/24/48 kHz PCM source; use 48 kHz capture" }
                    require(audioSetting.channelCount in 1..2)
                    if ("video" in tracks) VideoEncoderOptions.validate(videoSetting.options)
                    if ("video" in tracks && videoType == RtcVideoCodec.H264) {
                        require(videoSetting.profileLevel == VideoCodecProfileLevel.H264_BASELINE_3_1) { "RTC currently offers H.264 Baseline level 3.1" }
                        val options = videoSetting.options.toList()
                        Sdp.validateH264Sender(videoSetting.width and -2, videoSetting.height and -2, videoSetting.frameRate, videoSetting.bitRate)
                        val profile =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
                            } else {
                                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                            }
                        videoCodec.options = options + CodecOption(MediaFormat.KEY_PROFILE, profile)
                    }
                    if ("video" in tracks && videoType == RtcVideoCodec.VP9) {
                        require(videoSetting.profileLevel.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile0) { "RTC VP9 requires profile 0" }
                    }
                    audioCodec.sampleRate = audioSetting.sampleRate
                    audioCodec.channelCount = audioSetting.channelCount
                    videoCodec.bitRate = videoSetting.bitRate
                    videoCodec.surface = null
                    // Start only tracks accepted by the server. Base startRunning() would
                    // start every attached mixer source, including rejected media.
                    if ("audio" in tracks) audioCodec.startRunning()
                    if ("video" in tracks) videoCodec.startRunning()
                    check("audio" !in tracks || audioCodec.isRunning.get()) { "Opus encoder failed to start" }
                    check("video" !in tracks || videoCodec.isRunning.get()) { "${videoType.rtpName} encoder failed to start" }
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
                    super@RtcStream.startRunning()
                    if ("audio" in tracks) launch { decodeAudio(run) }
                    if ("video" in tracks) {
                        launch { if (videoType == RtcVideoCodec.H264) decodeVideo(run, peer) else decodeVpVideo(run) }
                        launch {
                            while (true) {
                                if (videoType == RtcVideoCodec.H264 || !run.hasVideoKeyframe) peer.requestKeyframe()
                                delay(2000)
                                if (videoType == RtcVideoCodec.H264 && run.hasVideoKeyframe) break
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
                    super@RtcStream.stopRunning()
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
        H264.validateParameterSets(run.videoConfiguration)
        var sps: ByteArray? = run.videoConfiguration.firstOrNull { it[0].toInt() and 31 == 7 }
        var pps: ByteArray? = run.videoConfiguration.firstOrNull { it[0].toInt() and 31 == 8 }
        var appliedSps: ByteArray? = null
        var appliedPps: ByteArray? = null
        var lastTime = Long.MIN_VALUE
        for (sample in run.video) {
            run.bytes.addAndGet(-sample.data.size.toLong())
            if (sample.timeUs <= lastTime) continue
            val units = H264.units(sample.data)
            H264.validateParameterSets(units)
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
                val dimensions = H264.dimensions(currentSps)
                configureVideo(
                    run,
                    dimensions,
                    listOf(
                        CodecOption("csd-0", ByteBuffer.wrap(H264.annexB(listOf(currentSps)))),
                        CodecOption("csd-1", ByteBuffer.wrap(H264.annexB(listOf(currentPps)))),
                    ),
                )
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

    private suspend fun decodeVpVideo(run: Run) {
        var dimensions: Pair<Int, Int>? = null
        var lastRtpTime = Long.MIN_VALUE
        var lastInputTime = Long.MIN_VALUE
        var recoveryRequested = false
        for (sample in run.video) {
            run.bytes.addAndGet(-sample.data.size.toLong())
            val inputTime = VpVideo.decoderTime(run.videoType, sample.timeUs, lastRtpTime, lastInputTime) ?: continue
            // Only bitstream metadata failures are recoverable. Decoder/surface errors
            // below still fail the session rather than being hidden as corrupt input.
            val (key, next) =
                try {
                    val key = VpVideo.keyframe(run.videoType, sample.data)
                    val size = if (key) VpVideo.dimensions(run.videoType, sample.data) else null
                    size?.let { run.videoLimits?.validateDimensions(it.first, it.second) }
                    key to size
                } catch (_: IllegalArgumentException) {
                    run.hasVideoKeyframe = false
                    recoveryRequested = true
                    continue
                }
            if (key) {
                if (dimensions != next) {
                    configureVideo(run, checkNotNull(next), emptyList())
                    dimensions = next
                }
                recoveryRequested = false
                run.hasVideoKeyframe = true
                run.keyframes.add(inputTime)
                while (run.keyframes.size > 64) run.keyframes.pollFirst()
            }
            if (dimensions == null || recoveryRequested) {
                run.hasVideoKeyframe = false
                recoveryRequested = true
                continue
            }
            lastRtpTime = sample.timeUs
            lastInputTime = inputTime
            if (run.videoType == RtcVideoCodec.VP9) run.videoTimestamps.remember(inputTime, sample.timeUs)
            feed(videoCodec.codec!!, run.videoIndices.receive(), sample.copy(timeUs = inputTime))
        }
    }

    private suspend fun configureVideo(
        run: Run,
        dimensions: Pair<Int, Int>,
        configuration: List<CodecOption>,
    ) {
        videoCodec.stopRunning()
        while (run.videoIndices.tryReceive().isSuccess) { }
        videoCodec.width = dimensions.first
        videoCodec.height = dimensions.second
        videoCodec.options = videoSetting.options.filterNot { it.key.startsWith("csd-") } + configuration
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
        withRtcDeadline(5000, "Decoder output surface was not attached") { attached.await() }
        run.videoNeedsSync.set(true)
        videoCodec.startRunning()
        check(videoCodec.isRunning.get()) { "${run.videoType.rtpName} decoder failed to start" }
        hasVideo = true
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
            val owner = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) audioCodec else videoCodec
            owner.withCurrentCodec(codec) {
                if (current !== run) return@withCurrentCodec
                val indices = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) run.audioIndices else run.videoIndices
                if (indices.trySend(index).isFailure) run.failed.complete(IOException("Decoder input queue capacity exceeded"))
            }
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
                    mediaLink.audioTrack = MediaFormatUtil.createAudioTrack(mediaFormat, audioUsage)
                } else if (!run.publishing && mime == MediaFormat.MIMETYPE_VIDEO_RAW) {
                    val size = Size(MediaFormatUtil.getWidth(mediaFormat), MediaFormatUtil.getHeight(mediaFormat))
                    if (run.videoType == RtcVideoCodec.H264) H264.validateFrameSize(size.width, size.height)
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
                val audio = mime == MediaFormat.MIMETYPE_AUDIO_RAW
                val firstVpOutput = !audio && run.videoType != RtcVideoCodec.H264 && run.videoNeedsSync.getAndSet(false)
                val sync = !audio && (run.keyframes.remove(info.presentationTimeUs) || firstVpOutput)
                val time = if (!audio && run.videoType == RtcVideoCodec.VP9) run.videoTimestamps.outputTime(info.presentationTimeUs) else info.presentationTimeUs
                queueOutputBuffer(if (audio) MediaType.AUDIO else MediaType.VIDEO, index, buffer, time, sync)
                return false
            }
            if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return true
            try {
                val audio = mime == MediaFormat.MIMETYPE_AUDIO_OPUS
                require(audio || mime == run.videoType.mime) { "Reconnect RTC before changing the video codec" }
                var data =
                    copy(
                        buffer.duplicate().apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                        },
                    )
                if (!audio && run.videoType == RtcVideoCodec.H264 && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
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

    private class Run(val publishing: Boolean, val tracks: Set<String>, val videoConfiguration: List<ByteArray>, val videoType: RtcVideoCodec, val videoLimits: VpLimits?) {
        val epochUs = System.nanoTime() / 1000
        val audioClock = RtpClock(48_000)
        val videoClock = RtpClock(90_000)
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
        val videoNeedsSync = AtomicBoolean(true)
        val videoTimestamps = VpVideo.Timestamps()

        @Volatile var hasVideoKeyframe = false
    }

    private companion object {
        const val MAX_FRAME = 4 * 1024 * 1024
        const val MAX_QUEUE = 8 * 1024 * 1024

        fun copy(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    }
}
