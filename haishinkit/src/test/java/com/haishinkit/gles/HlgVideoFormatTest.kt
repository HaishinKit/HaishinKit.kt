package com.haishinkit.gles

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.codec.VideoCodecProfileLevel
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HlgVideoFormatTest {
    @Test fun main10FormatsCarryLimitedBt2020HlgAndSdrRemainsUnchanged() {
        withCodec { codec ->
            assertFalse(format(codec, MediaFormat.MIMETYPE_VIDEO_AVC).containsKey(MediaFormat.KEY_COLOR_TRANSFER))
            codec.pixelTransform.screen = TestScreen(RuntimeEnvironment.getApplication())
            for (profile in listOf(VideoCodecProfileLevel.HEVC_MAIN10_4_1, VideoCodecProfileLevel.AV1_MAIN10_4_1)) {
                codec.profileLevel = profile
                val format = format(codec, profile.mime)
                assertEquals(MediaFormat.COLOR_STANDARD_BT2020, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                assertEquals(MediaFormat.COLOR_TRANSFER_HLG, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
                assertEquals(profile.profile, format.getInteger(MediaFormat.KEY_PROFILE))
            }
        }
    }

    @Test fun hlgRejectsEightBitAndUnsupportedCodecProfiles() {
        withCodec { codec ->
            codec.pixelTransform.screen = TestScreen(RuntimeEnvironment.getApplication())
            for (profile in listOf(VideoCodecProfileLevel.H264_BASELINE_3_1, VideoCodecProfileLevel.HEVC_MAIN_3_1, VideoCodecProfileLevel.AV1_MAIN8_3_1)) {
                codec.profileLevel = profile
                assertThrows(IllegalArgumentException::class.java) { format(codec, profile.mime) }
            }
        }
    }

    @Test
    @Config(sdk = [29])
    fun oldPlatformRejectsHlgEncoding() {
        withCodec { codec ->
            codec.pixelTransform.screen = TestScreen(RuntimeEnvironment.getApplication())
            codec.profileLevel = VideoCodecProfileLevel.HEVC_MAIN10_4_1
            assertThrows(IllegalArgumentException::class.java) { format(codec, MediaFormat.MIMETYPE_VIDEO_HEVC) }
        }
    }

    private fun format(
        codec: VideoCodec,
        mime: String,
    ): MediaFormat =
        try {
            VideoCodec::class.java.getDeclaredMethod("createMediaFormat", String::class.java).apply { isAccessible = true }.invoke(codec, mime) as MediaFormat
        } catch (cause: java.lang.reflect.InvocationTargetException) {
            throw cause.targetException
        }

    private fun withCodec(block: (VideoCodec) -> Unit) {
        val original = PixelTransform.factory
        PixelTransform.factory = { FakeTransform(it) }
        try {
            block(VideoCodec(RuntimeEnvironment.getApplication()).apply { mode = Codec.MODE_ENCODE })
        } finally {
            PixelTransform.factory = original
        }
    }

    private class TestScreen(context: Context) : Screen(context, DynamicRangeMode.HLG) {
        override fun readPixels(lambda: (Bitmap?) -> Unit) = lambda(null)

        override fun bind(screenObject: ScreenObject) {}

        override fun unbind(screenObject: ScreenObject) {}

        override fun attachVideo(
            track: Int,
            video: VideoSource?,
        ) {}
    }

    private class FakeTransform(override val context: Context) : PixelTransform {
        override var screen: Screen? = null
        override var surface: Surface? = null
        override var imageExtent = Size(0, 0)
        override var videoEffect: VideoEffect = DefaultVideoEffect.shared
        override var videoGravity = VideoGravity.RESIZE_ASPECT
        override var frameRate = 30
        override var backgroundColor = 0
    }
}
