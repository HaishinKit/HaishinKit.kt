package com.haishinkit.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.Codec
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaType
import com.haishinkit.stream.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin

/** Run with opusRtmpUrl pointing to an Enhanced RTMP server accepting live/opus-android-test. */
@RunWith(AndroidJUnit4::class)
class RtmpOpusDeviceTest {
    @Test fun nativeOpusPublishingAndPlaybackUseSharedAudioCodec() {
        val url = InstrumentationRegistry.getArguments().getString("opusRtmpUrl")
        assumeTrue("Requires an explicit test RTMP server", url != null)
        val context = InstrumentationRegistry.getInstrumentation().context
        val publishingConnection = RtmpConnection()
        val playingConnection = RtmpConnection()
        val publisher = RtmpStream(context, publishingConnection)
        val player = RtmpStream(context, playingConnection)
        val peak = AtomicInteger()
        val decoded = AtomicInteger()
        try {
            publisher.hasAudio = true
            publisher.audioSetting.mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS
            publisher.audioSetting.sampleRate = 48000
            publisher.audioSetting.channelCount = 2
            publishingConnection.connect(url!!)
            waitUntil { publisher.readyState == RtmpStream.ReadyState.OPEN }
            publisher.publish("opus-android-test")
            waitUntil { publisher.readyState == RtmpStream.ReadyState.PUBLISHING }
            // Publish some packets first so a late receiver gets the cached OpusHead.
            repeat(30) { feed(publisher, it) }
            playingConnection.connect(url)
            waitUntil { player.readyState == RtmpStream.ReadyState.OPEN }
            player.play("opus-android-test")
            val codec = audioCodec(player)
            val muxer = player.muxer
            codec.listener =
                object : Codec.Listener by muxer {
                    override fun onSampleOutput(
                        mime: String,
                        index: Int,
                        info: MediaCodec.BufferInfo,
                        buffer: ByteBuffer,
                    ): Boolean {
                        if (mime == MediaFormat.MIMETYPE_AUDIO_RAW && info.size > 0) {
                            val data = buffer.duplicate().order(ByteOrder.nativeOrder())
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            var value = 0
                            while (data.remaining() >= 2) value = maxOf(value, abs(data.short.toInt()))
                            peak.accumulateAndGet(value, ::maxOf)
                            decoded.incrementAndGet()
                        }
                        return muxer.onSampleOutput(mime, index, info, buffer)
                    }
                }
            var index = 30
            val deadline = SystemClock.elapsedRealtime() + 12000
            while ((decoded.get() < 10 || (player.mediaLink.audioTrack?.playbackHeadPosition ?: 0) == 0) && SystemClock.elapsedRealtime() < deadline) {
                feed(publisher, index++)
            }
            assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, audioCodec(publisher).outputMimeType)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, codec.inputMimeType)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_RAW, codec.outputMimeType)
            assertTrue("No decoded Opus PCM: ${decoded.get()}", decoded.get() >= 10)
            assertTrue("Decoded PCM was silent: ${peak.get()}", peak.get() > 1000)
            assertEquals(48000, player.mediaLink.audioTrack!!.sampleRate)
            assertTrue(player.mediaLink.audioTrack!!.playbackHeadPosition > 0)
            android.util.Log.i("RtmpOpusDeviceTest", "OPUS_ROUNDTRIP buffers=${decoded.get()} peak=${peak.get()} frames=${player.mediaLink.audioTrack!!.playbackHeadPosition}")
        } finally {
            player.close()
            publisher.close()
            playingConnection.close()
            publishingConnection.close()
            playingConnection.dispose()
            publishingConnection.dispose()
        }
    }

    private fun feed(
        stream: RtmpStream,
        packet: Int,
    ) {
        val pcm = ByteBuffer.allocateDirect(960 * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(960) { index ->
            val sample = (sin((packet * 960 + index) * 2.0 * Math.PI * 440 / 48000) * 8000).toInt().toShort()
            pcm.putShort(sample)
            pcm.putShort(sample)
        }
        pcm.flip()
        stream.append(MediaBuffer(MediaType.AUDIO, 0, pcm))
        SystemClock.sleep(20)
    }

    private fun audioCodec(stream: RtmpStream) =
        Stream::class.java.getDeclaredMethod("getAudioCodec").apply { isAccessible = true }.invoke(stream) as AudioCodec

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("RTMP operation timed out", condition())
    }
}
