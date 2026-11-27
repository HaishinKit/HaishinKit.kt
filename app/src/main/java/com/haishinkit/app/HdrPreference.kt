package com.haishinkit.app

import android.content.Context
import android.os.Build
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.screen.Screen

data class HdrSettings(
    val publishing: DynamicRangeMode = DynamicRangeMode.SDR,
    val playback: DynamicRangeMode = DynamicRangeMode.SDR,
) {
    val enabled: Boolean get() = publishing == DynamicRangeMode.HLG || playback == DynamicRangeMode.HLG
}

internal object HdrPreference {
    private const val STORE = "hdr"

    fun selected(context: Context): HdrSettings {
        if (Build.VERSION.SDK_INT < 33) return HdrSettings()
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        return HdrSettings(
            if (store.getBoolean("publishing", false)) DynamicRangeMode.HLG else DynamicRangeMode.SDR,
            if (store.getBoolean("playback", false)) DynamicRangeMode.HLG else DynamicRangeMode.SDR,
        )
    }

    fun validate(
        context: Context,
        settings: HdrSettings,
    ) {
        if (!settings.enabled) return
        unsupportedReason(settings, PublishingProtocol.fromUrl(Preference.shared.streamURL))?.let { throw IllegalArgumentException(context.getString(it)) }
        // Probe on the rendering worker before replacing any running session or preference.
        Screen.createOpenGL(context, DynamicRangeMode.HLG).dispose()
    }

    internal fun unsupportedReason(
        settings: HdrSettings,
        protocol: PublishingProtocol,
        sdk: Int = Build.VERSION.SDK_INT,
    ): Int? =
        when {
            !settings.enabled -> null
            sdk < 33 -> R.string.studio_hdr_android
            protocol != PublishingProtocol.RTMP -> R.string.studio_hdr_rtmp_only
            else -> null
        }

    fun save(
        context: Context,
        settings: HdrSettings,
    ) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean("publishing", settings.publishing == DynamicRangeMode.HLG)
            .putBoolean("playback", settings.playback == DynamicRangeMode.HLG).apply()
        if (settings.enabled) RendererPreference.save(context, Renderer.OPENGL)
    }
}
