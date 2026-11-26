package com.haishinkit.rtmp

import com.haishinkit.rtmp.message.RtmpAudioMessage
import com.haishinkit.rtmp.message.RtmpCommandMessage
import com.haishinkit.rtmp.message.RtmpMessage
import com.haishinkit.rtmp.message.RtmpVideoMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class RtmpCompactChunkTest {
    @Test fun repeatedCompactAacAndVideoMessagesReadFreshBodies() {
        for (video in listOf(false, true)) {
            val connection = RtmpConnection()
            val type: Byte = if (video) 9 else 8

            fun body(value: Byte) = if (video) byteArrayOf(0x91.toByte(), 0x61, 0x76, 0x30, 0x31, value) else byteArrayOf(0xAF.toByte(), 1, value)
            connection.listen(header(body(1), type, RtmpChunk.ZERO))
            connection.listen(header(body(2), type, RtmpChunk.ONE))
            for (value in byteArrayOf(3, 4, 5)) {
                connection.listen(compact(body(value)))
                val message = connection.messages[4]!!
                assertEquals(RtmpChunk.THREE, message.chunk)
                assertArrayEquals(byteArrayOf(value), data(message))
                assertEquals(0, message.payload.position())
            }
        }
    }

    @Test fun continuationChunksDoNotReplaceTheOriginalMessageHeaderType() {
        for (type in byteArrayOf(8, 9, 20)) {
            val connection = RtmpConnection()
            connection.socket.chunkSizeC = 4
            val first = body(type, false)
            val second = body(type, true)
            assertEquals(first.size, second.size)
            feedSplit(connection, first, type, newCompact = false)
            assertEquals(RtmpChunk.ZERO, connection.messages[4]!!.chunk)
            checkBody(connection.messages[4]!!, type, false)
            repeat(2) { index ->
                val updated = index == 0
                feedSplit(connection, if (updated) second else first, type, newCompact = true)
                assertEquals(RtmpChunk.THREE, connection.messages[4]!!.chunk)
                checkBody(connection.messages[4]!!, type, updated)
            }
        }
    }

    @Test fun incompleteCompactChunksPreserveAssemblyAndCanBeRetried() {
        val connection = RtmpConnection()
        connection.socket.chunkSizeC = 4
        val body = body(9, false)
        connection.listen(header(body, 9, RtmpChunk.ZERO, count = 4))
        val continuation = compact(body.copyOfRange(4, body.size))
        val fragment = continuation.duplicate().apply { limit(2) }
        connection.listen(fragment)
        assertEquals(0, fragment.position())
        assertEquals(4, connection.messages[4]!!.payload.position())
        assertEquals(RtmpChunk.ZERO, connection.messages[4]!!.chunk)
        connection.listen(continuation)
        checkBody(connection.messages[4]!!, 9, false)
        val missingFirstBody = ByteBuffer.wrap(byteArrayOf(0xC4.toByte()))
        connection.listen(missingFirstBody)
        assertEquals(0, missingFirstBody.position())
        assertEquals(0, connection.messages[4]!!.payload.position())
        assertEquals(RtmpChunk.ZERO, connection.messages[4]!!.chunk)
        feedSplit(connection, body(9, true), 9, newCompact = true)
        checkBody(connection.messages[4]!!, 9, true)
    }

    private fun feedSplit(
        connection: RtmpConnection,
        body: ByteArray,
        type: Byte,
        newCompact: Boolean,
    ) {
        connection.listen(if (newCompact) compact(body.copyOfRange(0, 4)) else header(body, type, RtmpChunk.ZERO, count = 4))
        var offset = 4
        while (offset < body.size) {
            val end = minOf(offset + 4, body.size)
            val bytes = compact(body.copyOfRange(offset, end))
            connection.listen(bytes)
            assertFalse(bytes.hasRemaining())
            offset = end
        }
    }

    private fun header(
        body: ByteArray,
        type: Byte,
        chunk: RtmpChunk,
        count: Int = body.size,
    ): ByteBuffer =
        ByteBuffer.allocate(12 + count).apply {
            put(((chunk.rawValue.toInt() shl 6) or 4).toByte())
            put(byteArrayOf(0, 0, 0))
            put(byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte(), type))
            if (chunk == RtmpChunk.ZERO) putInt(0)
            put(body, 0, count)
            flip()
        }

    private fun compact(body: ByteArray) = ByteBuffer.allocate(1 + body.size).put(0xC4.toByte()).put(body).apply { flip() }

    private fun body(
        type: Byte,
        updated: Boolean,
    ): ByteArray {
        val marker = if (updated) 2.toByte() else 1.toByte()
        return when (type.toInt()) {
            8 -> byteArrayOf(0xAF.toByte(), 1, marker, marker, marker, marker, marker, marker)
            9 -> byteArrayOf(0x91.toByte(), 0x61, 0x76, 0x30, 0x31, marker, marker, marker)
            else ->
                ByteBuffer.allocate(100).apply {
                    RtmpCommandMessage(RtmpObjectEncoding.AMF0).apply {
                        commandName = "ignore"
                        arguments = listOf(if (updated) "bravo" else "alpha")
                    }.encode(this)
                    flip()
                }.let { bytes -> ByteArray(bytes.remaining()).also { bytes.get(it) } }
        }
    }

    private fun checkBody(
        message: RtmpMessage,
        type: Byte,
        updated: Boolean,
    ) {
        when (type.toInt()) {
            20 -> assertEquals(listOf(if (updated) "bravo" else "alpha"), (message as RtmpCommandMessage).arguments)
            else -> {
                val raw = body(type, updated)
                assertArrayEquals(raw.copyOfRange(if (type == 8.toByte()) 2 else 5, raw.size), data(message))
            }
        }
    }

    private fun data(message: RtmpMessage) =
        when (message) {
            is RtmpAudioMessage -> message.data!!.array()
            is RtmpVideoMessage -> message.data!!.array()
            else -> error("Not a media message")
        }
}
