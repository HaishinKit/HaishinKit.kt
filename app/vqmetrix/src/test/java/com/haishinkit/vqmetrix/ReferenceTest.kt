package com.haishinkit.vqmetrix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class ReferenceTest {
    @Test
    fun referenceRoundTripPreservesEveryPlaneAndFrame() {
        val file = File.createTempFile("reference", ".y4m")
        val spec = ReferenceSpec(64, 64, 30, 3)
        try {
            Y4mWriter(file, spec).use { writer -> (0 until spec.frames).forEach { writer.append(TestPattern.frame(spec, it)) } }
            Y4mReader(file, spec).use { reader ->
                (0 until spec.frames).forEach { assertArrayEquals(TestPattern.frame(spec, it), reader.next()) }
                assertNull(reader.next())
            }
            assertFalse(TestPattern.frame(spec, 0).contentEquals(TestPattern.frame(spec, 1)))
            val original = file.readBytes()
            file.writeBytes(original.copyOf(original.size - 2))
            assertThrows(IllegalStateException::class.java) {
                Y4mReader(file, spec).use { reader -> repeat(3) { reader.next() } }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsAmbiguousBitratesAndInsecureReceiverAddresses() {
        assertEquals(listOf(500_000, 1_000_000, 2_000_000), BatchConfig.parseBitrates("0.5, 1, 2"))
        listOf("", "NaN", "1,1", "-1", "21", "0.0000001").forEach { input ->
            assertThrows(Exception::class.java) { BatchConfig.parseBitrates(input) }
        }
        assertEquals("https://example.com/vq/api/v1/runs", BatchUploader.endpoint("https://example.com/vq/").toString())
        assertEquals("http://192.168.1.2:8787/api/v1/runs", BatchUploader.endpoint("http://192.168.1.2:8787").toString())
        assertEquals("http://mac.local:8787/api/v1/runs", BatchUploader.endpoint("http://mac.local:8787").toString())
        listOf("http://8.8.8.8", "http://example.com", "https://user:pass@example.com", "https://example.com?token=x", "file:///tmp/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { BatchUploader.endpoint(it) }
        }
    }

    @Test
    fun discoveryRequiresCompatibleProtocolAndLocalHttpHost() {
        val txt = mapOf("protocol" to "1", "scheme" to "http", "host" to "studio.local")
        assertEquals("http://studio.local:8787", ReceiverDiscovery.endpoint("Studio", 8787, txt)?.baseUrl)
        assertEquals("http://192.168.1.2:8787", ReceiverDiscovery.endpoint("Studio", 8787, txt, "192.168.1.2")?.baseUrl)
        assertNull(ReceiverDiscovery.endpoint("Studio", 8787, txt + ("protocol" to "2")))
        assertNull(ReceiverDiscovery.endpoint("Studio", 8787, txt + ("host" to "example.com")))
        assertNull(ReceiverDiscovery.endpoint("Studio", 8787, txt + ("host" to "user@studio.local")))
        assertNull(ReceiverDiscovery.endpoint("Studio", 0, txt))
    }

    @Test
    fun paddedInterleavedChromaCopiesOnlyValidPixels() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 1, 88, 2, 77, 66, 55, 3, 44, 4, 33))
        val destination = ByteArray(6)
        YuvImages.copyPlaneToArray(source, 1, 6, 2, 2, 2, destination, 1)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 0), destination)
    }

    @Test
    fun presentationTimestampsDoNotAccumulateRoundingError() {
        assertEquals(0L, VariantEncoder.pts(0, 30))
        assertEquals(33_333L, VariantEncoder.pts(1, 30))
        assertEquals(1_000_000L, VariantEncoder.pts(30, 30))
        assertEquals(10_000_000L, VariantEncoder.pts(300, 30))
    }
}
