# HaishinKit RTC for Android

WHIP publishing and WHEP playback using libdatachannel and Android MediaCodec.
Requires Android 10 (API 29) or later. The core, RTMP, and SRT modules keep their
existing minimum Android versions.

## Supported media and signaling

- H.264 Baseline level 3.1, RTP packetization-mode 1; outgoing frames include SPS/PPS on keyframes.
- Opus audio. Capture PCM must match `stream.audioSetting` (48 kHz mono by default).
- HTTP(S) SDP POST, 201 response validation, relative/absolute Location, and DELETE on close/setup failure.
- Non-trickle ICE: candidates are gathered before POST. Configure STUN/TURN URLs through `RTCConfiguration`.
- Bearer authentication and 307/308 POST redirects. Authorization is removed across origins; HTTPS downgrade is rejected.
- RTCP sender/receiver reports, outgoing NACK responses, incoming PLI/FIR keyframe requests, and REMB bitrate limits.
- Audio-only and video-only publishing through `expectedMedias`; rejected tracks do not start encoders.
- Bounded native H.264 packet/byte retention before frame assembly, bounded media queues and reconnectable sessions. Observe `RTCSession.failure` for asynchronous failures.

This is an initial transport implementation. It does not implement trickle ICE/PATCH,
ICE restart, automatic ICE-server discovery from HTTP Link headers, simulcast, RTX,
TWCC/GCC bandwidth estimation, or adaptive jitter buffering/packet-loss concealment.
WHEP uses libdatachannel's depacketizers and the existing MediaLink playback scheduler.
RTP timestamps are extended across wraparound and initially anchored to arrival time;
RTCP sender-report timestamps are not yet used to refine audio/video synchronization.
Configure TURN explicitly when required. Validate interoperability and network-loss
behavior with the intended WHIP/WHEP server before production use.

## Publishing

Add the optional `rtc` module as a dependency. When publishing binaries, its Maven
artifact is `com.github.HaishinKit.HaishinKit~kt:rtc` with the same version as the other modules.
The app consuming this module must have minSdk 29 or later.

```kotlin
val configuration = RTCConfiguration(
    iceServers = listOf("stun:stun.example.com:3478"),
    bearerToken = token, // Omit when the endpoint does not require it.
)
val session = RTCSession(context, Uri.parse(whipUrl), configuration = configuration)
val mixer = MediaMixer(context)
val microphone = AudioRecordSource(context).apply { sampleRate = 48_000 }
// Obtain RECORD_AUDIO / CAMERA permissions before attaching capture sources.
mixer.attachAudio(0, microphone).getOrThrow()
mixer.attachVideo(0, Camera2Source(context)).getOrThrow()
mixer.registerOutput(session.stream)
mixer.startRunning()
session.connect().getOrThrow()

// At the end of the session:
session.close().getOrThrow()
mixer.unregisterOutput(session.stream)
session.stream.dispose()
mixer.dispose()
```

`expectedMedias = setOf(MediaType.AUDIO, MediaType.VIDEO)` can explicitly select
offered tracks before connecting, otherwise they are derived from the mixer sources.
Configure video dimensions, bitrate and frame rate through `stream.videoSetting`.
The initial implementation requires `H264_BASELINE_3_1`; use dimensions/frame rates
within that level's limits (up to 3,600 macroblocks/frame, 108,000 macroblocks/second,
and 14 Mbit/s). Incompatible H.264 profiles and receiver levels below 3.1 are rejected
before publishing; codec options cannot override the fixed profile/level. Audio settings must describe the actual PCM input;
this module does not resample captured audio.

## Playback

```kotlin
val session = RTCSession(
    context,
    Uri.parse(whepUrl),
    mode = StreamSession.Mode.PLAYBACK,
    configuration = configuration,
)
// Attach the playback view before connecting.
session.stream.registerOutput(streamView)
session.connect().getOrThrow()

// Later:
session.close().getOrThrow()
session.stream.unregisterOutput(streamView)
session.stream.dispose()
```

For the common builder API:

```kotlin
StreamSession.Builder.registerFactory(HTTPStreamSessionFactory(configuration))
val session = StreamSession.Builder(context, Uri.parse(endpoint))
    .setMode(StreamSession.Mode.PLAYBACK) // PUBLISH selects WHIP.
    .build()
```

Production HTTPS uses Android's normal certificate validation. Plain HTTP endpoints
also require the consuming app's Android network-security policy to permit cleartext.
HTTP connect/read timeouts are finite; cancellation can wait for an in-flight blocking
HTTP operation before cleanup finishes. Closing releases local codecs and transport
even if server DELETE fails; the failure is returned from `close()`.

## Building and validation

The NDK/CMake build pins libdatachannel 0.24.3 by commit and Mbed TLS 3.6.5 by SHA-256.
It builds arm64-v8a, armeabi-v7a, x86_64, and x86, with 16 KiB ELF segment alignment.
The first native build needs network access. License notices are bundled in AAR assets.
No separate software audio/video codec library is bundled.

```sh
./gradlew :rtc:assembleRelease :rtc:testDebugUnitTest :rtc:ktlintCheck
./gradlew :rtc:connectedDebugAndroidTest
```

The device tests cover ICE/DTLS/SRTP, Opus encoding and playback, reconnect and DELETE,
and H.264 encoding through WHIP followed by WHEP decoding with repeated resolution changes and a pixel-color check. Local connection and surface deadlines report failures while caller cancellation remains cancellation.
They use a local test endpoint and do not require microphone/camera access or an
external server. They do not replace interoperability tests against a production server.

For native integration tests on a host JVM, build `src/main/cpp` with CMake and
`RTC_JNI_INCLUDE_DIR` pointing to JNI headers (plus their platform headers where
required), then pass `-PrtcNativeLibraryDir=/absolute/path/to/native-build` to Gradle.
When using a standalone NDK `jni.h` on a host JVM, set `-DRTC_NDK_JNI=ON`.
Without that Gradle property, native JVM tests are explicitly skipped; device tests
always load the packaged Android library.

Offline source overrides for native builds are available through
`-PrtcDatachannelSourceDir=/path/to/libdatachannel` (including pinned submodules)
and `-PrtcMbedtlsSourceDir=/path/to/mbedtls`.

## Sample app

The sample app requires Android 10+. In **Preference**, enter the complete WHIP
endpoint in **Publish URL (RTMP / SRT / WHIP)**, then enter the matching stream's
WHEP endpoint in **WHEP playback URL**. The app does not append RTMP StreamName to
HTTP(S) URLs. Optional bearer authentication (without the `Bearer ` prefix) and
STUN/TURN URLs, one per line, apply to both sessions. Settings remain in memory
for the current app process. Local plain-HTTP endpoints are enabled in the sample.

Open **Publish**, grant camera/microphone permissions, and tap **GO LIVE** for WHIP.
Open **Playback** and tap **PLAY** for WHEP. Use **STOP** to disconnect or cancel
connection setup. Errors appear in the playback screen or a publishing message.
Use the server's actual, complete endpoints; WHIP and WHEP paths are not inferred
from each other. Settings are applied when reopening the corresponding tab.

The sample captures 48 kHz PCM and sets its stream/recorder audio settings to the
same rate. RTMP and SRT continue using AAC at that rate. Screen sharing uses the
same publishing endpoint and RTC configuration.

Host native tests also exercise bounded H.264 input retention and recovery:

```sh
cmake --build /path/to/native-build --target rtc_media_test
ctest --test-dir /path/to/native-build --output-on-failure
```
