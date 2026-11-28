package com.haishinkit.rnnoise

import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.media.source.PcmAudioSource

/**
 * Owns [source] while attached and disables the Android session's NoiseSuppressor for A/B tests.
 * Attach with an RNNoise processor factory. Failure to confirm OS suppression is off fails
 * this input instead of silently stacking effects. Headset firmware DSP is outside Android's
 * NoiseSuppressor API and may remain active. Do not share [source] with another track.
 */
class RnnoiseMicrophoneSource(val source: AudioRecordSource) : PcmAudioSource by source {
    enum class OsNoiseSuppression { UNCONFIRMED, UNAVAILABLE, DISABLED }

    @Volatile var osNoiseSuppression = OsNoiseSuppression.UNCONFIRMED
        private set
    private var effect: NoiseSuppressor? = null
    private var session = 0
    private var suppressionAvailable = false

    @Volatile private var opened = false
    private val guard = Any()

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        val result = source.open(mixer)
        if (result.isFailure) return result
        return try {
            synchronized(guard) {
                require(source.audioFormat.sampleRate == 48_000) { "Configure microphone input to 48 kHz before attaching RNNoise" }
                suppressionAvailable = NoiseSuppressor.isAvailable()
                disableOsSuppression()
                opened = true
            }
            Result.success(Unit)
        } catch (cause: Exception) {
            close()
            Result.failure(cause)
        }
    }

    override suspend fun close(): Result<Unit> {
        synchronized(guard) {
            opened = false
            runCatching { effect?.release() }
            effect = null
            session = 0
            osNoiseSuppression = OsNoiseSuppression.UNCONFIRMED
        }
        return source.close()
    }

    override fun read(track: Int): MediaBuffer {
        val result = source.read(track)
        synchronized(guard) {
            if (opened && result.payload?.hasRemaining() == true) disableOsSuppression()
        }
        return result
    }

    private fun disableOsSuppression() {
        osNoiseSuppression = OsNoiseSuppression.UNCONFIRMED
        if (!suppressionAvailable) {
            osNoiseSuppression = OsNoiseSuppression.UNAVAILABLE
            return
        }
        val currentSession = requireNotNull(source.audioSessionId)
        if (session != currentSession || effect == null) {
            effect?.release()
            effect = null
            session = currentSession
            effect = NoiseSuppressor.create(session)
        }
        val current = checkNotNull(effect) { "Cannot inspect Android noise suppression" }
        if (current.enabled) {
            check(current.hasControl() && current.setEnabled(false) == AudioEffect.SUCCESS) { "Cannot disable Android noise suppression" }
        }
        check(!current.enabled) { "Android noise suppression is still enabled" }
        osNoiseSuppression = OsNoiseSuppression.DISABLED
    }
}
