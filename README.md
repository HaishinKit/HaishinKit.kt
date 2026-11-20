# 📡 [HaishinKit](https://haishinkit.com/) for Android, [iOS, macOS, tvOS and visionOS](https://github.com/HaishinKit/HaishinKit.swift).

[![Android CI](https://github.com/HaishinKit/HaishinKit.kt/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/HaishinKit/HaishinKit.kt/actions/workflows/android.yml)
[![JitPack](https://jitpack.io/v/HaishinKit/HaishinKit~kt.svg)](https://jitpack.io/#HaishinKit/HaishinKit~kt)
[![Android API 23+](https://img.shields.io/badge/Android-API%2023%2B-3DDC84?logo=android&logoColor=white)](#-installation)
[![BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE.md)
[![Sponsor](https://img.shields.io/badge/Sponsor-%E2%9D%A4-ff69b4?logo=github)](https://github.com/sponsors/shogo4405)

A Kotlin library for capturing, composing, recording, publishing, and playing
camera and microphone streams on Android using RTMP/RTMPS, SRT, and WHIP/WHEP.

[API reference](https://docs.haishinkit.com/kt/latest/) ·
[Releases](https://github.com/HaishinKit/HaishinKit.kt/releases) ·
[Sample app](app/README.md)

## 🎨 Features

- RTMP/RTMPS publishing and playback, including H.264/AAC and Enhanced RTMP HEVC support.
- Experimental SRT publishing and playback with MPEG-TS, H.264/H.265, AAC, and encryption.
- Experimental WHIP publishing and WHEP playback with H.264/Opus over WebRTC.
- A common `StreamSession` interface for protocol-specific publishing and playback.
- Camera and microphone capture, multiple cameras, and streaming to multiple destinations.
- Video compositions with text, images, and optional Lottie animations; preview and recording outputs.
- Jetpack Compose integration and an optional experimental Vulkan output backend.

## 📦 Installation

Add JitPack alongside your existing repositories in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the core library and your transport module in your app's `build.gradle.kts`:

```kotlin
dependencies {
    val haishinkitVersion = "0.20.3"
    implementation("com.github.HaishinKit.HaishinKit~kt:haishinkit:$haishinkitVersion")
    implementation("com.github.HaishinKit.HaishinKit~kt:rtmp:$haishinkitVersion")
}
```

Use `HaishinKit~kt` in JitPack coordinates. Keep all modules on the same version.
The example uses the published `0.20.3` release; `main` is the development branch
and may include changes absent from that release or the published API reference.

| Module | Minimum Android | Purpose |
|---|---|---|
| `haishinkit` | 6.0 / API 23 | Required core: capture, composition, codecs, and recording |
| `rtmp` | 6.0 / API 23 | RTMP/RTMPS transport |
| `srt` | 6.0 / API 23 | Experimental SRT transport |
| `rtc` | 6.0 / API 23 | Experimental WHIP/WHEP transport |
| `compose` | 6.0 / API 23 | Jetpack Compose views |
| `lottie` | 6.0 / API 23 | Lottie animations in compositions |
| `vulkan` | 9.0 / API 28 | Experimental Vulkan output |

Add optional modules using the same coordinates, replacing `rtmp` with the module
name. RTC audio publishing requires an Opus encoder, provided by Android 10+.

## 📚 Documentation

Use the [Dokka API reference](https://docs.haishinkit.com/kt/latest/) for media
capture and composition, stream sessions, recording, and protocol APIs.
The module guides are included in generated documentation:

- [Core media APIs](haishinkit/module-docs.md)
- [RTMP/RTMPS](rtmp/module-docs.md)
- [SRT](srt/module-docs.md)
- [WHIP/WHEP](rtc/module-docs.md)
- [Vulkan output](vulkan/module-docs.md)

Source guides track `main`; consult the matching release tag when integrating a
published version.

## 🐾 Examples

The [Android sample app](app/README.md) demonstrates camera publishing, playback,
recording, screen sharing, and screen composition. It requires **Android 10
(API 29) or later**, plus a streaming server and endpoints for network testing.

```sh
git clone --recurse-submodules https://github.com/HaishinKit/HaishinKit.kt.git
cd HaishinKit.kt
```

Open the repository in Android Studio and run the `app` configuration. See the
[sample guide](app/README.md) for setup, permissions, and protocol settings.
When reporting a problem, check whether it also occurs in the sample app.

## 💖 Support

Technical support through Issues and Discussions is available to contributors,
academic researchers, and [sponsors](https://github.com/sponsors/shogo4405).
The $50/month sponsorship tier includes priority technical support.

## 🌏 Related projects

- [HaishinKit.swift](https://github.com/HaishinKit/HaishinKit.swift) — Apple platforms.
- [HaishinKit.dart](https://github.com/HaishinKit/HaishinKit.dart) — Flutter.

## 📜 License

[BSD 3-Clause](LICENSE.md). The optional RTC module bundles libdatachannel
(MPL-2.0) and other dependencies; see its [third-party notices](rtc/src/main/assets/libdatachannel/NOTICE).
