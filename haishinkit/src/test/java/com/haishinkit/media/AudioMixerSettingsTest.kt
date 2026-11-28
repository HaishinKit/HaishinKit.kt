package com.haishinkit.media

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioMixerSettingsTest {
    @Test fun originalJvmConstructorsAndCopyDescriptorsRemainCallable() {
        val type = AudioMixerSettings::class.java
        val boolean = Boolean::class.javaPrimitiveType!!
        val integer = Int::class.javaPrimitiveType!!
        assertEquals(AudioMixerSettings(true), type.getConstructor(boolean).newInstance(true))
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        assertEquals(AudioMixerSettings(), type.getConstructor(boolean, integer, marker).newInstance(true, 1, null))
        val selected = AudioMixerSettings(isMuted = true, sampleRate = 44100, channels = 1, tracks = mapOf(2 to AudioMixerTrackSettings(.4f)))
        assertEquals(selected.copy(isMuted = false), type.getMethod("copy", boolean).invoke(selected, false))
        val defaults = type.getMethod("copy\$default", type, boolean, integer, Any::class.java)
        assertEquals(selected, defaults.invoke(null, selected, false, 1, null))
        assertEquals(selected.copy(isMuted = false), defaults.invoke(null, selected, false, 0, null))
    }

    @Test fun defaultAndSelectedFormatsAndTrackControlsRoundTrip() {
        assertEquals(48_000, AudioMixerSettings().sampleRate)
        assertEquals(2, AudioMixerSettings().channels)
        val value = AudioMixerSettings(sampleRate = 44100, channels = 1, tracks = mapOf(0 to AudioMixerTrackSettings(.25f), -1 to AudioMixerTrackSettings(isMuted = true)))
        assertEquals(value, Json.decodeFromString<AudioMixerSettings>(Json.encodeToString(value)))
        assertEquals(AudioMixerSettings(isMuted = true), Json.decodeFromString<AudioMixerSettings>("{\"isMuted\":true}"))
    }

    @Test fun invalidFormatsAndGainAreRejectedBeforeCapture() {
        for (rate in listOf(0, -1, 1000000)) assertThrows(IllegalArgumentException::class.java) { AudioMixerSettings(sampleRate = rate) }
        for (channels in listOf(0, 3, 8)) assertThrows(IllegalArgumentException::class.java) { AudioMixerSettings(channels = channels) }
        for (gain in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 4.1f)) assertThrows(IllegalArgumentException::class.java) { AudioMixerTrackSettings(gain) }
    }
}
