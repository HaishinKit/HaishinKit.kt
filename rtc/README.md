# 📡 HaishinKit RTC for Android

WHIP publishing and WHEP playback using libdatachannel and Android MediaCodec.
The Android AAR packages the JNI library as `libhkrtc.so`.
Requires Android 6.0 (API 23) or later. The core, RTMP, and SRT modules keep their
existing minimum Android versions.

## 🎬 Supported media and signaling

- H.264 Baseline level 3.1, RTP packetization-mode 1; outgoing frames include SPS/PPS on keyframes.
- VP8 and VP9 profile 0 (8-bit, single spatial layer), using OS encoders/decoders. [Codec selection](module-docs.md#video-codec-selection) must match the endpoint; reconnect to change codecs.
- Opus audio, with RTP padding and padding-only probes removed before decoding. Capture PCM must match `stream.audioSetting` (48 kHz mono by default).
- HTTP(S) SDP POST, 201 response validation, relative/absolute Location, and DELETE on close/setup failure.
- Non-trickle ICE: candidates are gathered before POST. Configure STUN/TURN URLs through `RtcConfiguration`.
- Bearer authentication and 307/308 POST redirects. Authorization is removed across origins; HTTPS downgrade is rejected.
- RTCP sender/receiver reports, outgoing NACK responses, incoming PLI/FIR keyframe requests, and REMB bitrate limits.
- Audio-only and video-only publishing through `expectedMedias`; rejected tracks do not start encoders.
- Bounded native H.264/VP8/VP9 packet/byte retention before frame assembly, bounded media queues and reconnectable sessions. Observe `RtcSession.failure` for asynchronous failures.

This is an initial transport implementation. It does not implement trickle ICE/PATCH,
ICE restart, automatic ICE-server discovery from HTTP Link headers, simulcast, RTX,
TWCC/GCC bandwidth estimation, or adaptive jitter buffering/packet-loss concealment.
WHEP uses libdatachannel's depacketizers and the existing MediaLink playback scheduler.
RTP timestamps are extended across wraparound and initially anchored to arrival time;
RTCP sender-report timestamps are not yet used to refine audio/video synchronization.
Configure TURN explicitly when required. Validate interoperability and network-loss
behavior with the intended WHIP/WHEP server before production use.

## 📤 Publishing

Add the optional `rtc` module as a dependency. When publishing binaries, its Maven
artifact is `com.github.HaishinKit.HaishinKit~kt:rtc` with the same version as the other modules.
The app consuming this module must have minSdk 23 or later.
Audio publishing requires an available Opus encoder (provided by Android 10+).
AudioCodec checks availability when starting encoding and reports an explicit
error if no Opus encoder is installed. Video-only publishing and playback do
not require an Opus encoder.

```kotlin
val configuration = RtcConfiguration(
    iceServers = listOf("stun:stun.example.com:3478"),
    bearerToken = token, // Omit when the endpoint does not require it.
)
val session = RtcSession(context, Uri.parse(whipUrl), configuration = configuration)
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
before publishing. Codec options cannot override the validated profile, level, dimensions,
frame rate or bitrate; use the corresponding videoSetting fields. Audio settings must describe the actual PCM input;
this module does not resample captured audio.

## ▶️ Playback

```kotlin
val session = RtcSession(
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
StreamSession.Builder.registerFactory(HttpStreamSessionFactory(configuration))
val session = StreamSession.Builder(context, Uri.parse(endpoint))
    .setMode(StreamSession.Mode.PLAYBACK) // PUBLISH selects WHIP.
    .build()
```

Production HTTPS uses Android's normal certificate validation. Plain HTTP endpoints
also require the consuming app's Android network-security policy to permit cleartext.
HTTP redirects and response-body reads share an absolute `httpTimeoutMillis` deadline.
Cancellation disconnects active HTTP requests and joins their workers before resource
cleanup or reconnection. `connectionTimeoutMillis` bounds the entire WebRTC setup. Closing releases local codecs and transport
even if server DELETE fails; the failure is returned from `close()`.

<a id="building-and-validation"></a>

## 🛠️ Building and validation

The NDK/CMake build pins libdatachannel 0.24.8 by commit and Mbed TLS 3.6.8 by SHA-256.
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

## 📱 Sample app

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

Host native tests also exercise bounded H.264 input retention/recovery and padded Opus RTP packets:

```sh
cmake --build /path/to/native-build --target rtc_media_test
ctest --test-dir /path/to/native-build --output-on-failure
```

## 🔄 Bidirectional audio and text data channels

`RtcDuplexSession` is an audio-only `sendrecv` connection independent of WHIP/WHEP.
Its suspending `run` function accepts an SDP-exchange callback, a data-channel label,
text-event and connected callbacks. It stays active until cancellation or failure.
Register `outgoing` with a 48 kHz capture mixer; `incoming` plays decoded Opus with
voice-communication audio attributes. The streams have independent codecs, so
received audio cannot enter the outgoing encoder. `send` sends reliable text once
the connected callback runs. Binary messages are ignored.

The caller owns permission, microphone capture, communication audio routing, and
the job running the session. Cancel and join that job before releasing capture.
Closing either stream also cancels the session. Create a new session for each call;
`run` releases both streams on exit. Call `dispose()` if abandoning an instance
before `run` starts. Stop the capture mixer separately on failure or cancellation.

This backend does not include libwebrtc's software echo cancellation. Use headphones
until speaker echo and device-specific voice processing have been validated.
The host-native `NativePeerTest.duplexOpusAndUnicodeDataChannelRoundTrip` exercises
both audio directions plus Japanese, emoji and embedded-NUL text over ICE/DTLS.

## 🔌 WebSocket

`RtcWebSocket` exposes libdatachannel's WebSocket engine without an HTTP client
runtime dependency. Create an instance per connection, call suspending `connect`
with a `ws://` or `wss://` URL, then `send(String)` and suspending `receive()`.
Messages expose UTF-8 `text`, raw `data` and `isBinary`. Call `close()` in a
`finally` block; cancellation during `connect` also releases the native socket.

TLS and hostname verification are always enabled. WSS uses platform trust anchors
by default. An optional PEM CA bundle can be supplied for private trust domains.
Native callback and receive queues are each limited to 64 messages; individual
frames and native outgoing buffering are limited to 1 MiB. Callbacks are dispatched
in order off native worker threads so coroutine consumers can safely close the
socket from a callback-resumed continuation. A slow consumer or transport error
terminates receive with an exception. The caller must then close the socket and
create a new instance.
Native loopback tests cover Unicode/binary frames, trusted WSS, hostname rejection
and opening cancellation. MockWebServer and okhttp-tls are test dependencies only.
