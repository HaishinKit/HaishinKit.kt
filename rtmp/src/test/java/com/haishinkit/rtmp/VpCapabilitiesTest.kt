package com.haishinkit.rtmp

import android.media.MediaFormat
import com.haishinkit.rtmp.message.RtmpCommandMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [23, 29])
class VpCapabilitiesTest {
    @Before
    fun setUp() = ShadowMediaCodecList.reset()

    @After
    fun tearDown() = ShadowMediaCodecList.reset()

    @Test
    fun connectAdvertisesOnlyAvailableVpDecoders() {
        addCodec(MediaFormat.MIMETYPE_VIDEO_VP8, false)
        addCodec(MediaFormat.MIMETYPE_VIDEO_VP9, false)
        val message = RtmpConnection().createConnectionMessage(URI("rtmp://localhost/live")) as RtmpCommandMessage
        assertEquals(listOf("vp08", "vp09"), message.commandObject!!["fourCcList"])
    }

    @Test
    fun anEncoderDoesNotClaimReceiveSupport() {
        addCodec(MediaFormat.MIMETYPE_VIDEO_VP8, true)
        val message = RtmpConnection().createConnectionMessage(URI("rtmp://localhost/live")) as RtmpCommandMessage
        assertNull(message.commandObject!!["fourCcList"])
    }

    private fun addCodec(
        mime: String,
        encoder: Boolean,
    ) {
        val caps =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(mime, 1280, 720)).setIsEncoder(encoder)
                .setProfileLevels(emptyArray()).setColorFormats(intArrayOf(19)).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.$mime.$encoder").setIsEncoder(encoder).setCapabilities(caps).build())
    }
}
