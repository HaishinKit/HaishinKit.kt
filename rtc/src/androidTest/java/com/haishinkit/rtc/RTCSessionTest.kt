package com.haishinkit.rtc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import com.haishinkit.media.source.VideoSource
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
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RTCSessionTest {
    @Test fun whipEncodesOpusAndReconnectsWithoutLeakingServerResources() =
        runBlocking {
            val server = Endpoint(false)
            val context = InstrumentationRegistry.getInstrumentation().context
            val session = RTCSession(context, server.uri)
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
            val session = RTCSession(context, server.uri, StreamSession.Mode.PLAYBACK)
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

    @Test fun whipEncodesH264AndWhepRendersTheDecodedFrame() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().context
            val whip = Endpoint(false, audio = false, video = true)
            val publisher = RTCSession(context, whip.uri)
            val mixer = MediaMixer(context)
            publisher.stream.expectedMedias = setOf(MediaType.VIDEO)
            publisher.stream.videoSetting.width = 128
            publisher.stream.videoSetting.height = 96
            mixer.screen.frame = Rect(0, 0, 128, 96)
            mixer.screen.backgroundColor = Color.GREEN
            mixer.registerOutput(publisher.stream)
            var playback: RTCSession? = null
            val whep = Endpoint(true, audio = false, video = true)
            try {
                suspend fun captureKeyframe(
                    width: Int,
                    height: Int,
                ): ByteArray {
                    publisher.stream.videoSetting.width = width
                    publisher.stream.videoSetting.height = height
                    whip.frames.clear()
                    publisher.connect().getOrThrow()
                    val frame =
                        withTimeout(10_000) {
                            var key: ByteArray? = null
                            while (key == null) {
                                val candidate = whip.frames.poll()
                                if (candidate != null && H264.units(candidate).any { it[0].toInt() and 31 == 5 }) key = candidate
                                if (key == null) delay(20)
                            }
                            key
                        }
                    publisher.close().getOrThrow()
                    return frame
                }
                val keyframe = captureKeyframe(128, 96)
                val resizedKeyframe = captureKeyframe(256, 144)
                val receiver = RTCSession(context, whep.uri, StreamSession.Mode.PLAYBACK)
                playback = receiver
                receiver.connect().getOrThrow()
                repeat(48) {
                    whep.peer!!.send(false, if (it / 8 % 2 == 0) keyframe else resizedKeyframe, it * 100_000L)
                    delay(100)
                }
                assertTrue("WHEP failed: ${receiver.failure.value}", receiver.isConnected)
                assertEquals(256, receiver.stream.screen.frame.width())
                assertEquals(144, receiver.stream.screen.frame.height())
                val pixel = CompletableDeferred<Int>()
                receiver.stream.screen.readPixels { bitmap ->
                    if (bitmap == null) {
                        pixel.completeExceptionally(AssertionError("No decoded screen"))
                    } else {
                        pixel.complete(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                        bitmap.recycle()
                    }
                }
                val color = withTimeout(5000) { pixel.await() }
                assertTrue("Decoded pixel is not green: $color", Color.green(color) > 180 && Color.red(color) < 80 && Color.blue(color) < 80)
                receiver.close().getOrThrow()
                assertMissingSurfaceFailure(context, whep, keyframe)
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

    @Test fun closeDuringConnectionLeavesTheSessionReconnectable() =
        runBlocking {
            val endpoint = Endpoint(false)
            val context = InstrumentationRegistry.getInstrumentation().context
            val session = RTCSession(context, endpoint.uri)
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
            val session = RTCSession(context, endpoint.uri, configuration = RTCConfiguration(connectionTimeoutMillis = 1))
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
                val http = TestHTTPServer()
                val first = java.util.concurrent.atomic.AtomicBoolean(true)
                val headers = CompletableDeferred<Unit>()
                val deleted = AtomicInteger()
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
                val configuration = RTCConfiguration(connectionTimeoutMillis = if (deadline) 1000 else 30_000)
                val session = RTCSession(context, Uri.parse("http://127.0.0.1:${http.address.port}/whip"), configuration = configuration)
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
        var session: RTCSession? = null
        try {
            val waiting = RTCSession(context, endpoint.uri, StreamSession.Mode.PLAYBACK)
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

    private class Endpoint(
        private val publishing: Boolean,
        private val audio: Boolean = true,
        private val video: Boolean = false,
    ) {
        val uri: Uri
            get() = Uri.parse("http://127.0.0.1:${http.address.port}/endpoint")
        val frames = LinkedBlockingQueue<ByteArray>(64)
        val deleted = AtomicInteger()

        @Volatile var peer: NativePeer? = null
        private val http = TestHTTPServer()

        init {
            http.createContext("/endpoint") { request ->
                val answer = LinkedBlockingQueue<String>()
                val created =
                    NativePeer(publishing, audio, video, emptyArray(), { kind, _, text ->
                        if (kind == NativePeer.SDP) answer.offer(text)
                    }, { _, frame, _ -> frames.offer(frame) })
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
