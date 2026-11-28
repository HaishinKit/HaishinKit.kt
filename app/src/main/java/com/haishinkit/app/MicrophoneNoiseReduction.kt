package com.haishinkit.app

import com.haishinkit.media.audio.AudioProcessor
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.rnnoise.RnnoiseAudioProcessor
import com.haishinkit.rnnoise.RnnoiseMicrophoneSource
import java.util.concurrent.atomic.AtomicReference

/** Keep one factory identity across permission callbacks and live A/B changes. */
internal class MicrophoneNoiseReduction(microphone: AudioRecordSource) {
    val source = RnnoiseMicrophoneSource(microphone)
    val isUnconfirmed get() = source.osNoiseSuppression == RnnoiseMicrophoneSource.OsNoiseSuppression.UNCONFIRMED
    val factory =
        AudioProcessor.Factory { format ->
            RnnoiseAudioProcessor(format).also {
                synchronized(this) {
                    it.isBypassed = !enabled
                    processor.set(it)
                }
            }
        }

    @Volatile private var enabled = false
    private val processor = AtomicReference<RnnoiseAudioProcessor>()

    @Synchronized fun setEnabled(value: Boolean) {
        enabled = value
        processor.get()?.isBypassed = !value
    }
}
