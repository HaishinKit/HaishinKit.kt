package com.haishinkit.media.source

import android.content.Context
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.media.MediaMixer
import com.haishinkit.util.MediaFormatUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** A decoder session owned by AssetScreenObject; surfaces remain owned by the renderer. */
internal class AssetVideoSource(
    context: Context,
    uri: Uri,
    looping: () -> Boolean,
    predecessor: AssetVideoSource? = null,
    audio: AssetAudioSource? = null,
    stopped: (Throwable?, Boolean) -> Unit,
) : VideoSource {
    @Volatile override var surface: Surface? = null
        @Synchronized set(value) {
            val previous = field
            field = value
            if (previous != null && value == null) cancel()
        }
    override val videoSize: Size get() = metadata?.size ?: Size(0, 0)
    val displaySize: Size get() = metadata?.displaySize(outputCrop) ?: Size(0, 0)
    override val imageOrientation: ImageOrientation get() = metadata?.orientation ?: ImageOrientation.UP
    override val videoCropRect: Rect? get() = outputCrop?.let(::Rect)
    val isPrepared: Boolean get() = metadata != null
    val isRunning: Boolean get() = running.get()
    internal val presentationTimeUs: Long get() = positionUs

    private val context = context
    private val uri = uri
    private val looping = looping
    private val stopped = stopped
    private val audio = audio
    private val audioClock = AssetPlaybackClock()

    @Volatile private var predecessor = predecessor

    @Volatile private var metadata: Metadata? = null

    @Volatile private var outputCrop: Rect? = null

    @Volatile private var positionUs = 0L
    private val running = AtomicBoolean(true)
    private val cancelled = AtomicBoolean(false)
    private val worker = Thread(::read, "HaishinKit.Asset").apply { isDaemon = true }

    fun start() = worker.start()

    fun cancel() {
        audioClock.active = false
        cancelled.set(true)
        running.set(false)
        worker.interrupt()
    }

    override suspend fun open(mixer: MediaMixer): Result<Unit> = Result.success(Unit)

    override suspend fun close(): Result<Unit> =
        withContext(Dispatchers.IO) {
            cancel()
            awaitRelease()
            Result.success(Unit)
        }

    private fun read() {
        var ownedExtractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var failure: Throwable? = null
        try {
            predecessor?.awaitRelease()
            predecessor = null
            if (!running.get()) return
            val extractor = MediaExtractor().also { ownedExtractor = it }
            requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r")) { "The asset cannot be opened." }.use { descriptor ->
                if (descriptor.declaredLength < 0) {
                    extractor.setDataSource(descriptor.fileDescriptor)
                } else {
                    extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            requireNotNull(track) { "The asset has no video track." }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val meta = Metadata.from(format)
            require(meta.size.width > 0 && meta.size.height > 0) { "Invalid asset dimensions." }
            if (format.containsKey("color-transfer")) {
                require(format.getInteger("color-transfer") !in setOf(6, 7)) { "HDR asset playback is not supported; use an SDR video." }
            }
            // Apply file rotation once in VideoScreenObject, rather than also in the codec surface.
            format.setInteger("rotation-degrees", 0)
            val decoder = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)) { "No OS decoder supports this video." }
            if (!running.get()) return
            metadata = meta
            audio?.bind(uri, audioClock)
            while (running.get() && surface == null) LockSupport.parkNanos(10_000_000)
            if (!running.get()) return
            var currentSurface = checkNotNull(surface)
            codec = MediaCodec.createByCodecName(decoder)
            codec.configure(format, currentSurface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var firstOutputUs: Long? = null
            var lastInputUs = 0L
            var lastOutputUs = 0L
            var clockNanos = 0L
            while (running.get()) {
                val nextSurface = surface ?: break
                if (nextSurface !== currentSurface) {
                    codec.setOutputSurface(nextSurface)
                    currentSurface = nextSurface
                }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        (buffer as java.nio.Buffer).clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(index, 0, 0, addPositive(lastInputUs, meta.frameDurationUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted assets are not supported." }
                            lastInputUs = extractor.sampleTime
                            require(lastInputUs >= 0) { "Invalid asset sample time." }
                            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0
                            codec.queueInputBuffer(index, 0, count, lastInputUs, flags)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputCrop = MediaFormatUtil.getCropRect(codec.outputFormat)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else ->
                        if (index >= 0) {
                            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val render = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && (info.size > 0 || !end)
                            if (render) {
                                if (firstOutputUs == null) {
                                    firstOutputUs = info.presentationTimeUs
                                    clockNanos = System.nanoTime()
                                    audioClock.begin(info.presentationTimeUs, clockNanos)
                                }
                                lastOutputUs = info.presentationTimeUs
                                waitUntil(deadline(clockNanos, (info.presentationTimeUs - checkNotNull(firstOutputUs)).coerceAtLeast(0)))
                            }
                            codec.releaseOutputBuffer(index, render && running.get())
                            if (render && running.get()) positionUs = (info.presentationTimeUs - checkNotNull(firstOutputUs)).coerceAtLeast(0)
                            if (end) {
                                firstOutputUs?.let { first -> waitUntil(deadline(clockNanos, maxOf(meta.durationUs - first, lastOutputUs - first + meta.frameDurationUs))) }
                                if (!running.get() || !looping() || firstOutputUs == null) break
                                codec.flush()
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                inputEnded = false
                                firstOutputUs = null
                            }
                        }
                }
            }
        } catch (error: Exception) {
            if (!cancelled.get()) failure = error
        } finally {
            audioClock.active = false
            audio?.unbind(audioClock)
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            runCatching { ownedExtractor?.release() }
            try {
                stopped(failure, cancelled.get())
            } finally {
                running.set(false)
            }
        }
    }

    private fun awaitRelease() {
        var session: AssetVideoSource? = this
        while (session != null) {
            if (Thread.currentThread() !== session.worker) session.worker.join()
            session = session.predecessor
        }
    }

    private fun waitUntil(deadline: Long) {
        while (running.get()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            LockSupport.parkNanos(minOf(remaining, 20_000_000))
        }
    }

    private fun deadline(
        clock: Long,
        offsetUs: Long,
    ): Long = addPositive(clock, offsetUs.coerceAtLeast(0).also { require(it <= Long.MAX_VALUE / 1000) { "Invalid video timestamp." } } * 1000)

    private fun addPositive(
        value: Long,
        offset: Long,
    ): Long {
        require(offset >= 0 && value <= Long.MAX_VALUE - offset) { "Invalid video timestamp." }
        return value + offset
    }

    internal data class Metadata(
        val size: Size,
        val orientation: ImageOrientation,
        val durationUs: Long,
        val frameDurationUs: Long,
        val sampleAspectWidth: Int = 1,
        val sampleAspectHeight: Int = 1,
        val initialCrop: Rect? = null,
    ) {
        fun displaySize(outputCrop: Rect? = null): Size {
            val crop = (outputCrop ?: initialCrop)?.takeIf { it.left >= 0 && it.top >= 0 && it.right <= size.width && it.bottom <= size.height && !it.isEmpty }
            val width = crop?.width() ?: size.width
            val height = crop?.height() ?: size.height
            val displayWidth = (width.toLong() * sampleAspectWidth + sampleAspectHeight / 2) / sampleAspectHeight
            return Size(displayWidth.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(), height)
        }

        companion object {
            fun from(format: MediaFormat): Metadata {
                val rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
                val orientation =
                    when ((rotation % 360 + 360) % 360) {
                        90 -> ImageOrientation.LEFT
                        180 -> ImageOrientation.DOWN
                        270 -> ImageOrientation.RIGHT
                        else -> ImageOrientation.UP
                    }
                val rate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } else 30.0
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0) else 0L
                val sarWidth = if (format.containsKey("sar-width")) format.getInteger("sar-width") else 1
                val sarHeight = if (format.containsKey("sar-height")) format.getInteger("sar-height") else 1
                return Metadata(
                    Size(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT)),
                    orientation,
                    duration,
                    (1_000_000 / rate.takeIf { it.isFinite() && it > 0 }.let { it ?: 30.0 }).toLong().coerceAtLeast(1),
                    if (sarWidth > 0 && sarHeight > 0) sarWidth else 1,
                    if (sarWidth > 0 && sarHeight > 0) sarHeight else 1,
                    MediaFormatUtil.getCropRect(format),
                )
            }
        }
    }
}
