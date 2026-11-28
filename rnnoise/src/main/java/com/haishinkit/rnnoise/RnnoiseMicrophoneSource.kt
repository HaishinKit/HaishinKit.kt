package com.haishinkit.rnnoise

import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.media.source.PcmAudioSource
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Last open/read failure. Preserved on close and cleared when a new open begins. */
    @Volatile var lastFailure: Throwable? = null
        private set
    private var effect: NoiseSuppressor? = null
    private var session = 0
    private var suppressionAvailable = false

    private var suppressionWasEnabled = AtomicBoolean()

    @Volatile private var opened = false
    private val guard = Any()

    override suspend fun open(mixer: MediaMixer): Result<Unit> =
        try {
            synchronized(guard) {
                lastFailure = null
                // Prepare the recorder/session, but do not start capturing default-processed PCM.
                requireNotNull(source.audioRecord) { "Recording permission is required" }
                require(source.audioFormat.sampleRate == 48_000) { "Configure microphone input to 48 kHz before attaching RNNoise" }
                suppressionAvailable = requireNoiseSuppressionCapability(AudioEffect.queryEffects())
                disableOsSuppression()
                val enabledSignal = suppressionWasEnabled
                // Each capture owns its latch; stale callbacks cannot taint a later session.
                // Avoid taking our guard inside the framework listener lock.
                effect?.setEnableStatusListener { _, enabled -> if (enabled) enabledSignal.set(true) }
            }
            source.open(mixer).getOrThrow()
            synchronized(guard) {
                verifyOsSuppression()
                opened = true
            }
            Result.success(Unit)
        } catch (cause: Exception) {
            lastFailure = cause
            close()
            Result.failure(cause)
        }

    override suspend fun close(): Result<Unit> {
        synchronized(guard) {
            opened = false
            runCatching { effect?.setEnableStatusListener(null) }
            runCatching { effect?.release() }
            suppressionWasEnabled = AtomicBoolean()
            effect = null
            session = 0
            osNoiseSuppression = OsNoiseSuppression.UNCONFIRMED
        }
        return source.close()
    }

    override fun read(track: Int): MediaBuffer =
        try {
            synchronized(guard) { if (opened) verifyOsSuppression() }
            val result = source.read(track)
            synchronized(guard) {
                // A changed or re-enabled session may already have queued processed PCM. Never
                // return this block or attempt to repair it in place; close/reopen is required.
                if (opened && result.payload?.hasRemaining() == true) verifyOsSuppression()
            }
            result
        } catch (cause: Exception) {
            synchronized(guard) { if (opened) lastFailure = cause }
            throw cause
        }

    private fun verifyOsSuppression() {
        try {
            check(source.audioSessionId == session) { "Microphone session changed; reopen the input" }
            check(!suppressionWasEnabled.get()) { "Android noise suppression was re-enabled; reopen the input" }
            if (suppressionAvailable) {
                check(effect != null && effect?.enabled == false) { "Android noise suppression is enabled or unconfirmed; reopen the input" }
                osNoiseSuppression = OsNoiseSuppression.DISABLED
            } else {
                osNoiseSuppression = OsNoiseSuppression.UNAVAILABLE
            }
        } catch (cause: Exception) {
            osNoiseSuppression = OsNoiseSuppression.UNCONFIRMED
            throw cause
        }
    }

    private fun disableOsSuppression() {
        osNoiseSuppression = OsNoiseSuppression.UNCONFIRMED
        session = requireNotNull(source.audioSessionId)
        suppressionWasEnabled = AtomicBoolean()
        if (!suppressionAvailable) {
            osNoiseSuppression = OsNoiseSuppression.UNAVAILABLE
            return
        }
        effect?.setEnableStatusListener(null)
        effect?.release()
        effect = null
        effect = NoiseSuppressor.create(session)
        val current = checkNotNull(effect) { "Cannot inspect Android noise suppression" }
        if (current.enabled) {
            check(current.hasControl() && current.setEnabled(false) == AudioEffect.SUCCESS) { "Cannot disable Android noise suppression" }
        }
        check(!current.enabled) { "Android noise suppression is still enabled" }
        osNoiseSuppression = OsNoiseSuppression.DISABLED
    }
}
