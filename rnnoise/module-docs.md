# Module rnnoise

Optional RNNoise v0.2 input-track processing. Core HaishinKit has no dependency on this
module. RNNoise source and model are pinned and SHA-256 checked during the native build;
the app does not download a model at runtime. RNNoise's BSD license is included in the AAR
as `META-INF/LICENSE-RNNoise` and `META-INF/NOTICE-RNNoise` and must be retained in binary distributions.

## Microphone A/B comparison

```kotlin
val microphone = RnnoiseMicrophoneSource(
    AudioRecordSource(context).apply { sampleRate = 48_000 }
)
val noiseProcessor = java.util.concurrent.atomic.AtomicReference<RnnoiseAudioProcessor>()
mixer.attachAudio(0, microphone, AudioProcessor.Factory { format ->
    RnnoiseAudioProcessor(format).also { noiseProcessor.set(it) }
})
// A: delayed dry input; B: RNNoise. Both keep the same sample count and delay.
noiseProcessor.get()?.isBypassed = true  // A
noiseProcessor.get()?.isBypassed = false // B
```

Obtain `RECORD_AUDIO` permission. This module also declares the normal
`MODIFY_AUDIO_SETTINGS` permission for session-effect control. The microphone wrapper owns and closes its source.
It disables Android's session NoiseSuppressor before starting capture and checks the actual
enabled state before and after each read. A changed/re-enabled session fails the input
without delivering the affected block; close/reopen is required. If the state cannot be confirmed off,
only that input fails. `osNoiseSuppression` distinguishes disabled, unavailable and unconfirmed.
This cannot disable processing performed inside headset firmware. The system's default voice
processing is therefore a separate comparison, not a claim of raw microphone audio.

Only the selected input is processed. Asset audio and MediaProjection audio remain untouched
unless attached separately with a processor factory. Input must be 48 kHz, PCM16 or Float32,
mono or stereo; stereo uses independent channel states. The mixer's output rate/channel count
remain independently selectable. Other input rates fail explicitly.

## Latency and performance

The streaming adapter accepts arbitrary complete PCM frames and returns the same number.
It adds 10 ms framing to RNNoise's 20 ms analysis/lookahead delay. A 30 ms dry delay keeps A/B switching
on the same timeline. Initial buffering produces silence; stopping discards the final 30 ms.
No VAD gate or gain normalization is added. Discontinuities reset the filter. Processing is
performed on the input thread, before the common mixer; no extra rendering thread is created.
ARM64 enables RNNoise's NEON path. Reusable direct buffers avoid allocations at steady state.
`processedFrames` and `processingNanos` expose processing cost, including warm bypass mode.
A normal track with no processor does not run RNNoise.

For device acceptance, compare the same microphone/route and gain with OS suppression off:
fan-only noise, ordinary speech, soft speech and word endings. Check a visible clap or timed
marker for A/V offset and a longer run for drift. Log route/session/OS suppression state after
Bluetooth disconnect/reconnect, and repeat the listening comparison on built-in and Bluetooth
microphones. Synthetic tests cannot establish speech quality or headset firmware behavior.

## Sample controls

The camera publish tools include an RNNoise switch. Initially OFF uses the normal microphone.
The first ON starts comparison capture; subsequent ON/OFF changes use warm RNNoise/dry
paths with equal 30 ms delay and OS noise suppression disabled. Restore normal microphone
input to exit comparison mode and release the processor. Only microphone audio is affected.
