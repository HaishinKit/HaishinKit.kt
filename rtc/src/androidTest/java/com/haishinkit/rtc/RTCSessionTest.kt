package com.haishinkit.rtc

import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
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
import org.junit.Assert.assertTrue
import org.junit.Test
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
                publisher.connect().getOrThrow()
                val keyframe =
                    withTimeout(10_000) {
                        var frame: ByteArray? = null
                        while (frame == null) {
                            val candidate = whip.frames.poll()
                            if (candidate != null && H264.units(candidate).any { it[0].toInt() and 31 == 5 }) frame = candidate
                            if (frame == null) delay(20)
                        }
                        frame
                    }
                publisher.close().getOrThrow()
                val receiver = RTCSession(context, whep.uri, StreamSession.Mode.PLAYBACK)
                playback = receiver
                receiver.connect().getOrThrow()
                repeat(30) {
                    whep.peer!!.send(false, keyframe, it * 100_000L)
                    delay(100)
                }
                assertTrue("WHEP failed: ${receiver.failure.value}", receiver.isConnected)
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
