package com.haishinkit.util

import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaFormat
import android.os.Build
import android.util.Log

@Suppress("UNUSED")
object MediaFormatUtil {
    private const val TAG = "MediaFormatUtil"
    private const val CROP_LEFT = "crop-left"
    private const val CROP_RIGHT = "crop-right"
    private const val CROP_TOP = "crop-top"
    private const val CROP_BOTTOM = "crop-bottom"

    fun getWidth(format: MediaFormat): Int {
        var width = format.getInteger(MediaFormat.KEY_WIDTH)
        if (format.containsKey(CROP_LEFT) && format.containsKey(CROP_RIGHT)) {
            width = format.getInteger(CROP_RIGHT) + 1 - format.getInteger(CROP_LEFT)
        }
        return width
    }

    fun getHeight(format: MediaFormat): Int {
        var height = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey(CROP_TOP) && format.containsKey(CROP_BOTTOM)) {
            height = format.getInteger(CROP_BOTTOM) + 1 - format.getInteger(CROP_TOP)
        }
        return height
    }

    /** Converts inclusive decoder crop bounds to a Rect with exclusive right/bottom coordinates.
     * Returns null when crop metadata is missing, incomplete, invalid, or would overflow.
     */
    fun getCropRect(format: MediaFormat?): Rect? {
        if (format == null || !format.containsKey(CROP_LEFT) || !format.containsKey(CROP_TOP) ||
            !format.containsKey(CROP_RIGHT) || !format.containsKey(CROP_BOTTOM)
        ) {
            return null
        }
        val left = format.getInteger(CROP_LEFT)
        val top = format.getInteger(CROP_TOP)
        val right = format.getInteger(CROP_RIGHT)
        val bottom = format.getInteger(CROP_BOTTOM)
        if (left < 0 || top < 0 || right < left || bottom < top || right == Int.MAX_VALUE || bottom == Int.MAX_VALUE) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    fun createAudioTrack(mediaFormat: MediaFormat): AudioTrack {
        val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelMask =
            if (channelCount == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
        val bufferSize =
            AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        Log.d(
            TAG,
            "sampleRate=$sampleRate, channelCount=$channelCount, channelMask:$channelMask, bufferSize=$bufferSize",
        )
        try {
            return if (Build.VERSION_CODES.M <= Build.VERSION.SDK_INT) {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    ).setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .apply {
                        if (Build.VERSION_CODES.O <= Build.VERSION.SDK_INT) {
                            setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        }
                    }.build()
            } else {
                @Suppress("DEPRECATION")
                return AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                )
            }
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            return AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )
        }
    }
}
