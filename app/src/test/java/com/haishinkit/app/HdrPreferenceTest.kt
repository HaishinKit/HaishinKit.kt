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
        assertEquals(R.string.studio_hdr_rtmp_only, HdrPreference.unsupportedReason(HdrSettings(playback = DynamicRangeMode.HLG), PublishingProtocol.SRT, 33))
        assertEquals(null, HdrPreference.unsupportedReason(HdrSettings(), PublishingProtocol.SRT, 29))
    }

    @Test
    @Config(sdk = [29])
    fun olderDevicesKeepSdrAndRejectHlg() {
        val context = RuntimeEnvironment.getApplication()
        HdrPreference.save(context, HdrSettings(DynamicRangeMode.HLG, DynamicRangeMode.HLG))
        assertEquals(HdrSettings(), HdrPreference.selected(context))
        assertEquals(R.string.studio_hdr_android, HdrPreference.unsupportedReason(HdrSettings(playback = DynamicRangeMode.HLG), PublishingProtocol.RTMP, 29))
    }
}
