package com.haishinkit.rtc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.codec.Codec
import com.haishinkit.codec.CodecCapabilities
import com.haishinkit.codec.VideoCodecProfileLevel
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RtcSessionTest {
    @Test fun whipEncodesOpusAndReconnectsWithoutLeakingServerResources() =
        runBlocking {
            val server = Endpoint(false)
            val context = InstrumentationRegistry.getInstrumentation().context
            val session = RtcSession(context, server.uri)
            session.stream.expectedMedias = setOf(MediaType.AUDIO)
            try {
                repeat(2) {
                    session.connect().getOrThrow()
                    assertTrue(session.isConnected)
                    repeat(20) {
                        session.stream.append(MediaBuffer(MediaType.AUDIO, 0, ByteBuffer.allocateDirect(1920)))
                        delay(20)
                    }
                    assertNotNull("No encoded Opus reached the receiver", server.frames.poll(5, TimeUnit.SECONDS))
                    session.close().getOrThrow()
                    assertEquals(StreamSession.ReadyState.CLOSED, session.readyState.value)
                }
                assertEquals(2, server.deleted.get())
            } finally {
                session.close()
                session.stream.dispose()
                server.close()
            }
        }

    @Test fun whepDecodesOpusAndPlaysThroughAudioTrack() =
        runBlocking {
            val server = Endpoint(true)
            val context = InstrumentationRegistry.getInstrumentation().context
            val session = RtcSession(context, server.uri, StreamSession.Mode.PLAYBACK)
            try {
                session.connect().getOrThrow()
                // A valid Opus silence frame, with timestamps in microseconds.
                withTimeout(10_000) {
                    var timestamp = 0L
                    while ((session.stream.mediaLink.audioTrack?.playbackHeadPosition ?: 0) == 0) {
                        assertTrue("WHEP failed: ${session.failure.value}", session.isConnected)
                        server.peer!!.send(true, byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()), timestamp)
                        timestamp += 20_000
                        delay(20)
                    }
                }
                assertEquals(48_000, session.stream.mediaLink.audioTrack!!.sampleRate)
                assertTrue(session.isConnected)
                session.close().getOrThrow()
                assertEquals(1, server.deleted.get())
                assertEquals(StreamSession.ReadyState.CLOSED, session.readyState.value)
            } finally {
                session.close()
                session.stream.dispose()
                server.close()
            }
        }

    @Test fun whipEncodesH264AndWhepRendersTheDecodedFrame() = videoRoundTrip(RtcVideoCodec.H264)

    @Test fun whipEncodesVp8AndWhepRendersTheDecodedFrame() = videoRoundTrip(RtcVideoCodec.VP8)

    @Test fun whipEncodesVp9AndWhepRendersTheDecodedFrame() = videoRoundTrip(RtcVideoCodec.VP9)

    @Test fun closeDuringConnectionLeavesTheSessionReconnectable() =
        runBlocking {
            val endpoint = Endpoint(false)
            val context = InstrumentationRegistry.getInstrumentation().context
            val session = RtcSession(context, endpoint.uri)
            session.stream.expectedMedias = setOf(MediaType.AUDIO)
            try {
                repeat(5) {
                    val connecting = async { session.connect() }
                    withTimeout(5000) { session.readyState.first { it == StreamSession.ReadyState.CONNECTING } }
                    session.close().getOrThrow()
                    try {
                        connecting.await()
                    } catch (_: CancellationException) {
                    }
                    assertEquals(StreamSession.ReadyState.CLOSED, session.readyState.value)
                }
                session.connect().getOrThrow()
                assertTrue(session.isConnected)
                session.close().getOrThrow()
            } finally {
                session.close()
                session.stream.dispose()
                endpoint.close()
            }
        }

    @Test fun connectionTimeoutIsReportedAsFailureAndClosesTheSession() =
        runBlocking {
            val endpoint = Endpoint(false)
            val context = InstrumentationRegistry.getInstrumentation().context
            val session = RtcSession(context, endpoint.uri, configuration = RtcConfiguration(connectionTimeoutMillis = 1))
            session.stream.expectedMedias = setOf(MediaType.AUDIO)
            try {
                val result = session.connect()
                assertTrue("Expected an I/O failure, got $result", result.exceptionOrNull() is IOException)
                assertTrue(session.failure.value is IOException)
                assertEquals(StreamSession.ReadyState.CLOSED, session.readyState.value)
            } finally {
                session.close()
                session.stream.dispose()
                endpoint.close()
            }
        }

    @Test fun stopAndConnectionDeadlineAbortDrippingHttpBeforeReconnect() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().context
            for (deadline in listOf(false, true)) {
                val backend = Endpoint(false)
                val http = TestHttpServer()
                val first = java.util.concurrent.atomic.AtomicBoolean(true)
                val headers = CompletableDeferred<Unit>()
                val deleted = AtomicInteger()
                val keyframeRequests = AtomicInteger()
                http.createContext("/whip") { request ->
                    if (first.getAndSet(false)) {
                        request.sendDrippingSDP("/resource") { headers.complete(Unit) }
                    } else {
                        request.responseHeaders.add("Location", backend.uri.toString())
                        request.sendResponseHeaders(307, -1)
                        request.close()
                    }
                }
                http.createContext("/resource") { request ->
                    deleted.incrementAndGet()
                    request.sendResponseHeaders(204, -1)
                    request.close()
                }
                http.start()
                val configuration = RtcConfiguration(connectionTimeoutMillis = if (deadline) 1000 else 30_000)
                val session = RtcSession(context, Uri.parse("http://127.0.0.1:${http.address.port}/whip"), configuration = configuration)
                session.stream.expectedMedias = setOf(MediaType.AUDIO)
                try {
                    val connecting = async { session.connect() }
                    withTimeout(3000) { headers.await() }
                    delay(100) // Keep STOP inside an active body read, after Location is received.
                    val start = System.nanoTime()
                    if (deadline) {
                        val result = withTimeout(3000) { connecting.await() }
                        assertTrue(result.exceptionOrNull() is IOException)
                        assertEquals("RTC connection timed out", session.failure.value?.message)
                    } else {
                        withTimeout(2000) { session.close().getOrThrow() }
                        try {
                            connecting.await()
                        } catch (_: CancellationException) {
                        }
                        assertNull(session.failure.value)
                    }
                    assertTrue("HTTP cancellation waited for the dripping body", (System.nanoTime() - start) / 1_000_000 < 2500)
                    assertEquals(StreamSession.ReadyState.CLOSED, session.readyState.value)
                    assertEquals(1, deleted.get())
                    session.connect().getOrThrow()
                    assertTrue(session.isConnected)
                    session.close().getOrThrow()
                    assertEquals(1, backend.deleted.get())
                } finally {
                    session.close()
                    session.stream.dispose()
                    http.stop()
                    backend.close()
                }
            }
        }

    @Test fun vp9HiddenKeyframeStartsPlaybackWithSharedRtpTime() = videoRoundTrip(RtcVideoCodec.VP9, 0L)

    @Test fun vp9HiddenFutureReferenceStartsEarlierVisiblePlayback() = videoRoundTrip(RtcVideoCodec.VP9, 100_000L)

    @Test fun vp8RecoveryRetriesAndRejectsOversizedHeadersBeforeReplacingDecoder() = videoRoundTrip(RtcVideoCodec.VP8, recovery = true)

    @Test fun corruptVpHeadersDoNotStopOpusPlayback() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().context
            for (codec in listOf(RtcVideoCodec.VP8, RtcVideoCodec.VP9)) {
                val endpoint = Endpoint(true, video = true, videoCodec = codec)
                val receiver = RtcSession(context, endpoint.uri, StreamSession.Mode.PLAYBACK, RtcConfiguration(playbackVideoCodec = codec))
                try {
                    receiver.connect().getOrThrow()
                    endpoint.peer!!.send(false, byteArrayOf(if (codec == RtcVideoCodec.VP9) 0x82.toByte() else 0), 0)
                    withTimeout(5000) {
                        var timestamp = 0L
                        while ((receiver.stream.mediaLink.audioTrack?.playbackHeadPosition ?: 0) == 0) {
                            assertTrue("Corrupt VP header stopped audio: ${receiver.failure.value}", receiver.isConnected)
                            endpoint.peer!!.send(true, byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()), timestamp)
                            timestamp += 20_000
                            delay(20)
                        }
                    }
                    val before = receiver.stream.mediaLink.audioTrack!!.playbackHeadPosition
                    val frame = Rect(receiver.stream.screen.frame)
                    endpoint.peer!!.send(false, oversizedVpHeader(codec), 1_000_000)
                    withTimeout(5000) {
                        var timestamp = 1_000_000L
                        while (receiver.stream.mediaLink.audioTrack!!.playbackHeadPosition <= before) {
                            assertTrue(receiver.isConnected)
                            endpoint.peer!!.send(true, byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()), timestamp)
                            timestamp += 20_000
                            delay(20)
                        }
                    }
                    delay(200)
                    assertTrue("Oversized VP header stopped audio: ${receiver.failure.value}", receiver.isConnected)
                    assertEquals(frame, receiver.stream.screen.frame)
                } finally {
                    receiver.close()
                    receiver.stream.dispose()
                    endpoint.close()
                }
            }
        }

    private fun videoRoundTrip(
        codec: RtcVideoCodec,
        hiddenOffsetUs: Long? = null,
        recovery: Boolean = false,
    ) =
        runBlocking {
            assumeTrue("OS encoder and decoder required for ${codec.rtpName}", CodecCapabilities.isCodecSupportedByType(Codec.MODE_ENCODE, codec.mime) && CodecCapabilities.isCodecSupportedByType(Codec.MODE_DECODE, codec.mime))
            val context = InstrumentationRegistry.getInstrumentation().context
            val whip = Endpoint(false, audio = false, video = true, videoCodec = codec)
            val publisher = RtcSession(context, whip.uri)
            publisher.stream.videoSetting.profileLevel =
                when (codec) {
                    RtcVideoCodec.H264 -> VideoCodecProfileLevel.H264_BASELINE_3_1
                    RtcVideoCodec.VP8 -> VideoCodecProfileLevel.VP8_MAIN
                    RtcVideoCodec.VP9 -> VideoCodecProfileLevel.VP9_PROFILE0_31
                }
            val mixer = MediaMixer(context)
            publisher.stream.expectedMedias = setOf(MediaType.VIDEO)
            publisher.stream.videoSetting.width = 128
            publisher.stream.videoSetting.height = 96
            mixer.screen.frame = Rect(0, 0, 128, 96)
            mixer.screen.addChild(
                ImageScreenObject().apply {
                    bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                },
            )
            mixer.registerOutput(publisher.stream)
            var playback: RtcSession? = null
            val whep = Endpoint(true, audio = false, video = true, videoCodec = codec)
            try {
                suspend fun captureKeyframe(
                    width: Int,
                    height: Int,
                ): ByteArray {
                    publisher.stream.videoSetting.width = width
                    publisher.stream.videoSetting.height = height
                    whip.frames.clear()
                    publisher.connect().getOrThrow()
                    val encoder = com.haishinkit.stream.Stream::class.java.getDeclaredMethod("getVideoCodec").apply { isAccessible = true }.invoke(publisher.stream) as com.haishinkit.codec.VideoCodec
                    assertEquals(publisher.stream.videoSetting.bitRate, encoder.bitRate)
                    publisher.stream.requestBitrate(100_000)
                    withTimeout(2_000) { while (encoder.bitRate != 100_000) delay(10) }
                    // Surface encoders may emit the initial blank buffer before the mixer
                    // submits the test signal. Capture a fresh keyframe after warm-up.
                    delay(300)
                    awaitGreen(mixer.screen, "encoder source")
                    whip.frames.clear()
                    publisher.stream.requestKeyframe()
                    val frame =
                        withTimeout(10_000) {
                            var key: ByteArray? = null
                            while (key == null) {
                                val candidate = whip.frames.poll()
                                if (candidate != null && (if (codec == RtcVideoCodec.H264) H264.units(candidate).any { it[0].toInt() and 31 == 5 } else VpVideo.keyframe(codec, candidate))) key = candidate
                                if (key == null) delay(20)
                            }
                            key
                        }
                    publisher.close().getOrThrow()
                    return frame
                }
                val keyframe = captureKeyframe(128, 96)
                val resizedKeyframe = captureKeyframe(256, 144)
                val receiver = RtcSession(context, whep.uri, StreamSession.Mode.PLAYBACK, RtcConfiguration(playbackVideoCodec = codec))
                playback = receiver
                receiver.connect().getOrThrow()
                val connectedAt = System.nanoTime()
                if (codec != RtcVideoCodec.H264) {
                    whep.peer!!.send(false, byteArrayOf(if (codec == RtcVideoCodec.VP9) 0x82.toByte() else 0), 0)
                    delay(200)
                    assertTrue("Corrupt VP header closed WHEP: ${receiver.failure.value}", receiver.isConnected)
                }
                var videoBase = 100_000L
                if (recovery) {
                    whep.peer!!.send(false, keyframe, videoBase)
                    // Wait until the startup PLI loop would have exited in the old code.
                    delay(2300)
                    assertEquals(128, receiver.stream.screen.frame.width())
                    val requests = whep.keyframeRequests.get()
                    whep.peer!!.send(false, oversizedVpHeader(codec), 2_400_000L)
                    // Ignore the first recovery request; no keyframe is sent yet.
                    withTimeout(6000) {
                        while (whep.keyframeRequests.get() < requests + 2) delay(20)
                    }
                    assertTrue("Oversized header closed WHEP: ${receiver.failure.value}", receiver.isConnected)
                    assertEquals("Oversized header replaced the working decoder", 128, receiver.stream.screen.frame.width())
                    videoBase = (System.nanoTime() - connectedAt) / 1000 + 100_000
                }
                if (hiddenOffsetUs == null) {
                    repeat(48) {
                        whep.peer!!.send(false, if (it / 8 % 2 == 0) keyframe else resizedKeyframe, videoBase + it * 100_000L)
                        delay(100)
                    }
                } else {
                    val hidden = keyframe.copyOf().apply { this[0] = (this[0].toInt() and 0xfd).toByte() }
                    whep.peer!!.send(false, hidden, 1_000_000L + hiddenOffsetUs)
                    // Standard show_existing_frame=1, frame_to_show_map_idx=0.
                    // No visible keyframe is ever sent to open the renderer gate.
                    repeat(20) {
                        whep.peer!!.send(false, byteArrayOf(0x88.toByte()), 1_000_000L + it * 100_000L)
                        delay(100)
                    }
                }
                assertTrue("WHEP failed: ${receiver.failure.value}", receiver.isConnected)
                assertEquals(if (hiddenOffsetUs == null) 256 else 128, receiver.stream.screen.frame.width())
                assertEquals(if (hiddenOffsetUs == null) 144 else 96, receiver.stream.screen.frame.height())
                awaitGreen(receiver.stream.screen, "decoded ${codec.rtpName}")
                receiver.close().getOrThrow()
                if (codec == RtcVideoCodec.H264) assertMissingSurfaceFailure(context, whep, keyframe)
            } finally {
                playback?.close()
                playback?.stream?.dispose()
                publisher.close()
                mixer.unregisterOutput(publisher.stream)
                publisher.stream.dispose()
                mixer.dispose()
                whip.close()
                whep.close()
            }
        }

    private suspend fun assertMissingSurfaceFailure(
        context: Context,
        endpoint: Endpoint,
        keyframe: ByteArray,
    ) {
        val originalFactory = Screen.factory
        Screen.factory = { application ->
            object : Screen(application) {
                override fun readPixels(lambda: (Bitmap?) -> Unit) = lambda(null)

                override fun bind(screenObject: ScreenObject) {}

                override fun unbind(screenObject: ScreenObject) {}

                override fun attachVideo(
                    track: Int,
                    video: VideoSource?,
                ) {}
            }
        }
        var session: RtcSession? = null
        try {
            val waiting = RtcSession(context, endpoint.uri, StreamSession.Mode.PLAYBACK)
            session = waiting
            waiting.connect().getOrThrow()
            endpoint.peer!!.send(false, keyframe, 0)
            withTimeout(10_000) { waiting.readyState.first { it == StreamSession.ReadyState.CLOSED } }
            assertTrue(waiting.failure.value is IOException)
            assertEquals("Decoder output surface was not attached", waiting.failure.value?.message)
        } finally {
            session?.close()
            session?.stream?.dispose()
            Screen.factory = originalFactory
        }
    }

    private suspend fun awaitGreen(
        screen: Screen,
        label: String,
    ) {
        var last: Int? = null
        try {
            withTimeout(5000) {
                while (true) {
                    val pixel = CompletableDeferred<Int?>()
                    screen.readPixels { bitmap ->
                        pixel.complete(bitmap?.getPixel(bitmap.width / 2, bitmap.height / 2))
                        bitmap?.recycle()
                    }
                    last = pixel.await()
                    if (last != null && Color.green(last!!) > 180 && Color.red(last!!) < 80 && Color.blue(last!!) < 80) break
                    delay(20)
                }
            }
        } catch (cause: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$label did not display the green signal (last pixel $last)", cause)
        }
    }

    private fun oversizedVpHeader(codec: RtcVideoCodec): ByteArray {
        if (codec == RtcVideoCodec.VP8) return byteArrayOf(0x10, 0, 0, 0x9d.toByte(), 1, 0x2a, 0, 8, 0, 8)
        val bits = "10000010" + "010010011000001101000010" + "0000" + 2047.toString(2).padStart(16, '0').repeat(2)
        return bits.padEnd((bits.length + 7) / 8 * 8, '0').chunked(8).map { it.toInt(2).toByte() }.toByteArray()
    }

    private class Endpoint(
        private val publishing: Boolean,
        private val audio: Boolean = true,
        private val video: Boolean = false,
        private val videoCodec: RtcVideoCodec = RtcVideoCodec.H264,
    ) {
        val uri: Uri
            get() = Uri.parse("http://127.0.0.1:${http.address.port}/endpoint")
        val frames = LinkedBlockingQueue<ByteArray>(64)
        val deleted = AtomicInteger()
        val keyframeRequests = AtomicInteger()

        @Volatile var peer: NativePeer? = null
        private val http = TestHttpServer()

        init {
            http.createContext("/endpoint") { request ->
                val answer = LinkedBlockingQueue<String>()
                val created =
                    NativePeer(publishing, audio, video, emptyArray(), { kind, _, text ->
                        if (kind == NativePeer.SDP) answer.offer(text)
                        if (kind == NativePeer.KEYFRAME) keyframeRequests.incrementAndGet()
                    }, { _, frame, _ -> frames.offer(frame) }, videoCodec = videoCodec)
                peer = created
                created.acceptOffer(request.requestBody.bufferedReader().readText())
                val sdp = requireNotNull(answer.poll(10, TimeUnit.SECONDS))
                // Source media is selected independently of any additional SDP sections.
                request.responseHeaders.add("Location", "/resource")
                request.responseHeaders.add("Content-Type", "application/sdp")
                val body = sdp.toByteArray()
                request.sendResponseHeaders(201, body.size.toLong())
                request.responseBody.use { it.write(body) }
            }
            http.createContext("/resource") { request ->
                assertEquals("DELETE", request.requestMethod)
                deleted.incrementAndGet()
                peer?.close()
                peer = null
                request.sendResponseHeaders(204, -1)
                request.close()
            }
            http.start()
        }

        fun close() {
            peer?.close()
            http.stop()
        }
    }
}
