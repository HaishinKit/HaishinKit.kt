package com.haishinkit.app

import com.haishinkit.graphics.DynamicRangeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HdrPreferenceTest {
    @Test fun onlyRtmpUrlsWithAHostPermitHlg() {
        val settings = HdrSettings(DynamicRangeMode.HLG, DynamicRangeMode.HLG)
        for (url in listOf("", "ftp://host/live", "https://host/live", "srt://host:9999", "rtmp://", "rtmp:///live")) {
            assertEquals(url, R.string.studio_hdr_rtmp_only, HdrPreference.unsupportedReason(settings, url, 33))
        }
        for (url in listOf("rtmp://host/live", " RTMPS://host/live ")) {
            assertEquals(url, null, HdrPreference.unsupportedReason(settings, url, 33))
        }
    }

    @Test fun graphicsSupportDoesNotDependOnTheEditedDestination() {
        val settings = HdrSettings(DynamicRangeMode.HLG, DynamicRangeMode.HLG)
        val context = RuntimeEnvironment.getApplication()
        HdrPreference.save(context, settings)
        assertEquals(null, HdrPreference.graphicsUnsupportedReason(settings, 33))
        assertEquals(R.string.studio_hdr_rtmp_only, HdrPreference.unsupportedReason(settings, "srt://host:9999", 33))
        assertEquals(settings, HdrPreference.selected(context))
    }

    @Test fun directionsPersistIndependentlyAndHlgSelectsOpenGl() {
        val context = RuntimeEnvironment.getApplication()
        RendererPreference.save(context, Renderer.VULKAN)
        HdrPreference.save(context, HdrSettings(DynamicRangeMode.HLG, DynamicRangeMode.SDR))
        assertEquals(HdrSettings(DynamicRangeMode.HLG, DynamicRangeMode.SDR), HdrPreference.selected(context))
        assertEquals(Renderer.OPENGL, RendererPreference.selected(context))
        HdrPreference.save(context, HdrSettings(DynamicRangeMode.SDR, DynamicRangeMode.HLG))
        assertEquals(HdrSettings(DynamicRangeMode.SDR, DynamicRangeMode.HLG), HdrPreference.selected(context))
        HdrPreference.save(context, HdrSettings())
        assertEquals(HdrSettings(), HdrPreference.selected(context))
    }

    @Test fun unsupportedTransportIsRejectedBeforeOpeningGraphics() {
        assertEquals(R.string.studio_hdr_rtmp_only, HdrPreference.unsupportedReason(HdrSettings(playback = DynamicRangeMode.HLG), "srt://127.0.0.1:9999", 33))
        assertEquals(null, HdrPreference.unsupportedReason(HdrSettings(), "srt://127.0.0.1:9999", 29))
    }

    @Test
    @Config(sdk = [29])
    fun olderDevicesKeepSdrAndRejectHlg() {
        val context = RuntimeEnvironment.getApplication()
        HdrPreference.save(context, HdrSettings(DynamicRangeMode.HLG, DynamicRangeMode.HLG))
        assertEquals(HdrSettings(), HdrPreference.selected(context))
        assertEquals(R.string.studio_hdr_android, HdrPreference.unsupportedReason(HdrSettings(playback = DynamicRangeMode.HLG), "rtmp://example.com/live", 29))
    }
}
