package com.haishinkit.app

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class DeviceCodecCatalogTest {
    @Test
    fun keepsEncodingAndDecodingSeparateAndIncludesUnavailableCommonCodecs() {
        val catalog =
            DeviceCodecCatalog.read(
                listOf(
                    info("hardware.av1.decoder", "video/av01", encoder = false, hardware = true),
                    info("software.av1.encoder", "video/av01", encoder = true, hardware = false),
                ),
            )
        val av1 = catalog.first { it.mime == "video/av01" }
        assertEquals(listOf("software.av1.encoder"), av1.encoders.map { it.name })
        assertEquals(CodecAcceleration.SOFTWARE, av1.encoders.single().acceleration)
        assertEquals(listOf("hardware.av1.decoder"), av1.decoders.map { it.name })
        assertEquals(CodecAcceleration.HARDWARE, av1.decoders.single().acceleration)
        val h264 = catalog.first { it.mime == "video/avc" }
        assertTrue(h264.encoders.isEmpty())
        assertTrue(h264.decoders.isEmpty())
    }

    @Test
    fun includesOtherDeviceCodecsAndDeduplicatesImplementations() {
        val decoder = info("audio.decoder", "audio/vorbis", encoder = false, hardware = false)
        val catalog = DeviceCodecCatalog.read(listOf(decoder, decoder))
        assertEquals(1, catalog.count { it.mime == "audio/vorbis" })
        assertEquals(1, catalog.first { it.mime == "audio/vorbis" }.decoders.size)
    }

    @Test
    fun doesNotGuessHardwareWhenThePlatformDoesNotClassifyIt() {
        val codec = info("OMX.vendor.video.decoder", "video/avc", encoder = false, hardware = null)
        val h264 = DeviceCodecCatalog.read(listOf(codec)).first { it.mime == "video/avc" }
        assertEquals(CodecAcceleration.UNKNOWN, h264.decoders.single().acceleration)
    }

    private fun info(
        name: String,
        mime: String,
        encoder: Boolean,
        hardware: Boolean?,
    ): MediaCodecInfo {
        val format = if (mime.startsWith("video/")) MediaFormat.createVideoFormat(mime, 640, 360) else MediaFormat.createAudioFormat(mime, 48000, 2)
        val capabilities =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(format).setIsEncoder(encoder).setProfileLevels(emptyArray()).apply { if (mime.startsWith("video/")) setColorFormats(intArrayOf(19)) }.build()
        return MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(encoder)
            .setIsHardwareAccelerated(hardware == true).setIsSoftwareOnly(hardware == false).setCapabilities(capabilities).build()
    }
}
