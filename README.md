# HaishinKit for Android, [iOS, macOS, tvOS and visionOS](https://github.com/HaishinKit/HaishinKit.swift).

[![GitHub license](https://img.shields.io/badge/license-New%20BSD-blue.svg)](https://raw.githubusercontent.com/HaishinKit/HaishinKit.kt/master/LICENSE.md)
[![](https://jitpack.io/v/HaishinKit/HaishinKit~kt.svg)](https://jitpack.io/#HaishinKit/HaishinKit~kt)
[![GitHub Sponsor](https://img.shields.io/static/v1?label=Sponsor&message=%E2%9D%A4&logo=GitHub&color=ff69b4)](https://github.com/sponsors/shogo4405)

* Camera and microphone streaming library via RTMP/RTMPS, SRT and WHIP/WHEP for Android.
* [API Documentation](https://docs.haishinkit.com/kt/latest/)

## 💖 Sponsors

Do you need additional support? Technical support on Issues and Discussions is provided only to
contributors and academic researchers of HaishinKit. By becoming a sponsor, we can provide the
support you need.

Sponsor: [$50 per month](https://github.com/sponsors/shogo4405): Technical support via GitHub
Issues/Discussions with priority response.

## 🎨 Features
- **Protocols** ✨Publish and play back over RTMP/RTMPS, experimental SRT (MPEG-TS with H.264/H.265 and AAC), or experimental WHIP/WHEP (WebRTC with H.264 and Opus).
- **Stream sessions** ✨Use a common `StreamSession` API for RTMP, SRT, WHIP and WHEP publishing and playback.
- **Multi Camera access** ✨Support multi camera access.
- **Multi Streaming** ✨Allowing live streaming to separate services. Views also support this, enabling the verification of raw video data.
- **Video mixing** ✨Possible to display any text or bitmap on a video during broadcasting or viewing. This allows for various applications such as watermarking and time display.

## 🐾 Examples
- Reference implementation app for RTMP/SRT publishing and playback, WHIP publishing and WHEP playback. The sample requires Android 10 (API 29) or later; the core and RTMP/SRT libraries still support API 23+.
- If an issue occurs, please check whether it also happens in the examples app.

```sh
git clone https://github.com/HaishinKit/HaishinKit.kt.git
cd HaishinKit.kt

# Open [Android Studio] -> [Open] ...
```

### Try WHIP and WHEP in the sample

1. Open **Preference** and enter the complete WHIP endpoint in **Publish URL (RTMP / SRT / WHIP)**. An `http://` or `https://` URL selects WebRTC.
2. Enter the matching stream's complete endpoint in **WHEP playback URL**. RTMP StreamName is ignored for HTTP(S); WHIP and WHEP paths are supplied by your server.
3. Set **Bearer token (optional)** without the `Bearer ` prefix, and **STUN / TURN servers (optional)** with one URL per line, if required. These settings apply to both WHIP and WHEP.
4. Open **Publish**, grant camera/microphone permissions and tap **GO LIVE** to send with WHIP. Open **Playback** and tap **PLAY** to receive with WHEP. **STOP** disconnects or cancels connection setup.

The sample allows local plain-HTTP endpoints and captures audio at 48 kHz. Settings
are kept for the current app process and are applied when reopening each tab.
Keep Publish open and use a second device/player for continuous publish-and-watch tests.
See the [sample app guide](app/README.md) for build commands and other protocols, and
[RTC setup](rtc/README.md) for authentication, ICE and implementation limits.

## 🔧 Usage


### Gradle dependency

**JitPack**

- A common mistake is trying to use implementation `com.github.HaishinKit.**HaishinKit.kt**`, which
  does not work. The correct form is implementation `com.github.HaishinKit.**HaishinKit~kt**`.
- Use a version available on JitPack and replace `x.x.x` with that version.

Add JitPack to your project's `settings.gradle` repositories:

```gradle
dependencyResolutionManagement {
  repositories {
    maven { url 'https://jitpack.io' }
  }
}
```

Add dependencies to your app's `build.gradle`:

```gradle
dependencies {
  implementation 'com.github.HaishinKit.HaishinKit~kt:haishinkit:x.x.x'
  // Choose the transport modules your app uses.
  implementation 'com.github.HaishinKit.HaishinKit~kt:rtmp:x.x.x'
  implementation 'com.github.HaishinKit.HaishinKit~kt:srt:x.x.x'
  // Optional WHIP/WHEP (API 29+).
  // implementation 'com.github.HaishinKit.HaishinKit~kt:rtc:x.x.x'
  // Optional UI and composition modules.
  implementation 'com.github.HaishinKit.HaishinKit~kt:compose:x.x.x'
  implementation 'com.github.HaishinKit.HaishinKit~kt:lottie:x.x.x'
  // Optional Vulkan output (API 28+).
  // implementation 'com.github.HaishinKit.HaishinKit~kt:vulkan:x.x.x'
}
```

### Dependencies

| -          | minSdk | Android | Requirements | Status | Description                                                              |
|:-----------|:-------|:--------|:-------------|:-------|:-------------------------------------------------------------------------|
| haishinkit | 23+    | 6.0     | Require      | Stable | It's the base module for HaishinKit.                                     |
| rtmp       | 23+    | 6.0     | Optional     | Stable | RTMP/RTMPS publishing and playback.                                      |
| srt        | 23+    | 6.0     | Optional     | Experimental | SRT publishing and playback with MPEG-TS and encryption support. |
| rtc        | 29+    | 10      | Optional     | Experimental | WHIP publishing and WHEP playback with H.264/Opus and libdatachannel. |
| compose    | 23+    | 6.0     | Optional     | Beta   | It's support for a composable component for HaishinKit.                  |
| lottie     | 23+    | 6.0     | Optional     | Beta   | It's a module for embedding Lottie animations into live streaming video. |
| vulkan     | 28+    | 9.0     | Optional     | Experimental | Vulkan output backend with OpenGL fallback. |

Add `rtmp`, `srt`, or `rtc` for the network protocols your app needs. Use the same version for all
artifacts and choose a release that includes the modules you need.

### Android manifest
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### Prerequisites

```kt
ActivityCompat.requestPermissions(
    this, arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ), 1
)
```

### RTMP and SRT stream sessions

Register the factories for the protocols your app uses, then build a session with
the complete stream URI. Call the suspending methods from your app's coroutine scope.

```kotlin
import android.net.Uri
import com.haishinkit.rtmp.RtmpStreamSessionFactory
import com.haishinkit.srt.SrtStreamSessionFactory
import com.haishinkit.stream.StreamSession

StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)
StreamSession.Builder.registerFactory(SrtStreamSessionFactory)

val session = StreamSession.Builder(context, Uri.parse("srt://192.168.1.10:9710"))
    .setMode(StreamSession.Mode.PUBLISH) // Use PLAYBACK to receive media.
    .build()
// Before connecting, attach session.stream to your MediaMixer for publishing,
// or attach a StreamView output to session.stream for playback.
session.connect().onFailure { error -> /* Handle connection/startup failure. */ }
// When finished:
session.close()
```

For RTMP/RTMPS, pass a complete URI such as `rtmps://example.com/live/streamName`.
Observe `session.readyState` for connection state changes.

### Experimental SRT module

The `:srt` module supports encrypted SRT connections and MPEG-TS publishing and
playback through `SrtConnection`, `SrtStream`, and `SrtStreamSessionFactory`.
Caller, listener, and rendezvous socket modes are supported. To listen for one peer,
use `srt://:9710?mode=listener`. Socket mode is independent of publishing/playback;
connect a caller to a listener, or configure both peers for rendezvous.

Pass the complete SRT URL, including options such as `streamid`, `latency`
(microseconds), or `passphrase`. A separate RTMP stream name is not used.
See [Android SRT setup](https://github.com/HaishinKit/HaishinKit.kt/blob/master/srt/README.md)
for requirements, build commands, encryption options, and example app usage.

### WHIP and WHEP stream sessions

Add `:rtc` and set your app's `minSdk` to 29 or later. A complete WHIP or WHEP
HTTP(S) endpoint is used directly; no separate RTMP stream name is appended.

```kotlin
import android.net.Uri
import com.haishinkit.rtc.HTTPStreamSessionFactory
import com.haishinkit.rtc.RTCConfiguration
import com.haishinkit.stream.StreamSession

val rtcConfiguration = RTCConfiguration(
    // Set iceServers / bearerToken when required by your server and network.
)
StreamSession.Builder.registerFactory(HTTPStreamSessionFactory(rtcConfiguration))
val session = StreamSession.Builder(context, Uri.parse(endpoint))
    .setMode(StreamSession.Mode.PUBLISH) // WHIP. Use PLAYBACK with a WHEP endpoint.
    .build()
// Publishing: register session.stream with a MediaMixer and use 48 kHz PCM capture.
// Playback: register a StreamView output with session.stream before connecting.
session.connect().getOrThrow()
// When finished:
session.close().getOrThrow()
session.stream.dispose()
```

### Experimental WHIP/WHEP module


The `:rtc` module provides `RTCSession` and `HTTPStreamSessionFactory` for WHIP
publishing and WHEP playback over HTTP(S). It requires Android 10+, uses
libdatachannel for WebRTC transport and MediaCodec for H.264/Opus. The other modules
retain their existing minimum Android versions. Configure audio capture to 48 kHz
for its default Opus settings.

See [Android WHIP/WHEP setup](rtc/README.md) for publishing/playback examples,
STUN/TURN and authentication settings, build commands and current limitations.
The sample app requires Android 10+. Enter the WHIP and WHEP endpoints in Preference,
then use Publish → GO LIVE or Playback → PLAY.

### Optional Vulkan output

Android API 28+ can select the Vulkan output backend for publishing, recording,
and preview. Screen composition and effects use the existing OpenGL renderer.
See [the Vulkan module guide](https://github.com/HaishinKit/HaishinKit.kt/blob/master/vulkan/module-docs.md)
for setup, capability requirements, OpenGL fallback, and device validation.

## 🌏 Related projects

| Project name                                                                                    | Notes                                                         | License                                                                                                          |
|-------------------------------------------------------------------------------------------------|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| [HaishinKit for iOS, macOS, tvOS and visionOS.](https://github.com/HaishinKit/HaishinKit.swift) | Camera and microphone streaming library for Apple platforms. | [BSD 3-Clause "New" or "Revised" License](https://github.com/HaishinKit/HaishinKit.swift/blob/master/LICENSE.md) |
| [HaishinKit for Flutter.](https://github.com/HaishinKit/HaishinKit.dart)                        | Camera and Microphone streaming library via RTMP for Flutter. | [BSD 3-Clause "New" or "Revised" License](https://github.com/HaishinKit/HaishinKit.dart/blob/master/LICENSE.md)  |

## 📜 License

BSD-3-Clause

The optional RTC module includes libdatachannel (MPL-2.0) and other third-party
dependencies. Their license texts and source links are bundled in the AAR under
`assets/libdatachannel`; see the [included notices](rtc/src/main/assets/libdatachannel/NOTICE).
