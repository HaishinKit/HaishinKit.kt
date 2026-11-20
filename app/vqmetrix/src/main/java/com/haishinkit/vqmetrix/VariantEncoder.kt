package com.haishinkit.vqmetrix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

data class EncodedVariant(
    val file: File,
    val bitrate: Int,
    val codec: String,
    val codecName: String,
    val hardwareAccelerated: Boolean,
    val outputPtsUs: List<Long>,
    val encodedBytes: Long,
    val performance: JSONObject,
)

class VariantEncoder(
    private val context: Context,
) {
    suspend fun encode(
        reference: File,
        captured: CapturedReference,
        target: File,
        bitrate: Int,
        format: String,
        progress: (Int) -> Unit,
    ): EncodedVariant =
        withContext(Dispatchers.IO) {
            val spec = captured.spec
            val telemetry = EncodingTelemetry(context, spec.frames, spec.fps)
            val mime = if (format == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val mediaFormat =
                MediaFormat.createVideoFormat(mime, spec.width, spec.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    setInteger(MediaFormat.KEY_PROFILE, if (format == "hevc") MediaCodecInfo.CodecProfileLevel.HEVCProfileMain else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    if (captured.range == "limited") setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    if (captured.matrix == "bt709") setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
            val name = checkNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(mediaFormat)) { "No compatible $format YUV encoder" }
            val native = MediaCodec.createByCodecName(name)
            var muxer: MediaMuxer? = null
            var started = false
            var running = false
            val outputPts = ArrayList<Long>()
            var bytes = 0L
            try {
                val output = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = output
                native.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                native.start()
                running = true
                var track = -1
                var inputIndex = 0
                var inputEnded = false
                var outputEnded = false
                val info = MediaCodec.BufferInfo()
                Y4mReader(reference, spec).use { reader ->
                    withTimeout(90_000) {
                        while (!outputEnded) {
                            currentCoroutineContext().ensureActive()
                            if (!inputEnded) {
                                val index = native.dequeueInputBuffer(10_000)
                                if (index >= 0) {
                                    val data = reader.next()
                                    if (data == null) {
                                        native.queueInputBuffer(index, 0, 0, pts(spec.frames, spec.fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputEnded = true
                                    } else {
                                        val image = checkNotNull(native.getInputImage(index)) { "Encoder does not support YUV input images" }
                                        YuvImages.write(image, data, spec)
                                        val timestamp = pts(inputIndex++, spec.fps)
                                        telemetry.input(timestamp)
                                        native.queueInputBuffer(index, 0, data.size, timestamp, 0)
                                    }
                                }
                            }
                            when (val index = native.dequeueOutputBuffer(info, 10_000)) {
                                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    check(!started) { "Unexpected encoder format change" }
                                    track = output.addTrack(native.outputFormat)
                                    output.start()
                                    started = true
                                }
                                in 0..Int.MAX_VALUE -> {
                                    try {
                                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                            telemetry.output(info.presentationTimeUs)
                                            check(started)
                                            output.writeSampleData(track, checkNotNull(native.getOutputBuffer(index)), info)
                                            outputPts.add(info.presentationTimeUs)
                                            bytes += info.size
                                            progress(outputPts.size)
                                        }
                                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    } finally {
                                        native.releaseOutputBuffer(index, false)
                                    }
                                }
                            }
                        }
                    }
                }
                check(outputPts.size == spec.frames) { "Encoder produced ${outputPts.size}/${spec.frames} frames" }
                check(outputPts == (0 until spec.frames).map { pts(it, spec.fps) }) { "Encoder timestamps differ from the reference" }
                output.stop()
                started = false
                EncodedVariant(target, bitrate, format, name, native.codecInfo.isHardwareAccelerated, outputPts.toList(), bytes, telemetry.finish())
            } finally {
                if (running) runCatching { native.stop() }
                native.release()
                try {
                    if (started) runCatching { muxer?.stop() }
                } finally {
                    muxer?.release()
                }
            }
        }

    companion object {
        fun pts(
            index: Int,
            fps: Int,
        ): Long = index.toLong() * 1_000_000 / fps
    }
}
