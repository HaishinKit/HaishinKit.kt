# HaishinKit for Android, [iOS, macOS, tvOS and visionOS](https://github.com/HaishinKit/HaishinKit.swift).

[![GitHub license](https://img.shields.io/badge/license-New%20BSD-blue.svg)](https://raw.githubusercontent.com/HaishinKit/HaishinKit.kt/master/LICENSE.md)
[![](https://jitpack.io/v/HaishinKit/HaishinKit~kt.svg)](https://jitpack.io/#HaishinKit/HaishinKit~kt)
[![GitHub Sponsor](https://img.shields.io/static/v1?label=Sponsor&message=%E2%9D%A4&logo=GitHub&color=ff69b4)](https://github.com/sponsors/shogo4405)

* Camera and Microphone streaming library via RTMP for Android.
* [API Documentation](https://docs.haishinkit.com/kt/latest/)

## 💖 Sponsors

Do you need additional support? Technical support on Issues and Discussions is provided only to
contributors and academic researchers of HaishinKit. By becoming a sponsor, we can provide the
support you need.

Sponsor: [$50 per month](https://github.com/sponsors/shogo4405): Technical support via GitHub
Issues/Discussions with priority response.

## 🎨 Features
- **Protocols** ✨Publish and playback feature are available RTMP.
- **Multi Camera access** ✨Support multi camera access.
- **Multi Streaming** ✨Allowing live streaming to separate services. Views also support this, enabling the verification of raw video data.
- **Video mixing** ✨Possible to display any text or bitmap on a video during broadcasting or viewing. This allows for various applications such as watermarking and time display.

## 🐾 Examples
- Reference implementation app for live streaming `publish` and `playback`.
- If an issue occurs, please check whether it also happens in the examples app.

```sh
git clone https://github.com/HaishinKit/HaishinKit.kt.git
cd HaishinKit.kt

# Open [Android Studio] -> [Open] ...
```

## 🔧 Usage

### Gradle dependency

**JitPack**

- A common mistake is trying to use implementation 'com.github.HaishinKit.**HaishinKit.kt**', which
  does not work. The correct form is implementation 'com.github.HaishinKit.**HaishinKit~kt**'.
- In older versions, there may be cases where Jetpack is not supported. If it's not available,
  please give up and use the latest version.

```gradle
allprojects {
  repositories {
    maven { url 'https://jitpack.io' }
  }
}

dependencies {
  implementation 'com.github.HaishinKit.HaishinKit~kt:haishinkit:x.x.x'
  implementation 'com.github.HaishinKit.HaishinKit~kt:rtmp:x.x.x'
  implementation 'com.github.HaishinKit.HaishinKit~kt:compose:x.x.x'
  implementation 'com.github.HaishinKit.HaishinKit~kt:lottie:x.x.x'
}
```

### Dependencies

| -          | minSdk | Android | Requirements | Status | Description                                                              |
|:-----------|:-------|:--------|:-------------|:-------|:-------------------------------------------------------------------------|
| haishinkit | 21+    | 5       | Require      | Stable | It's the base module for HaishinKit.                                     |
| rtmp       | 21+    | 5       | Require      | Stable | It's support for an rtmp streaming.                                      |
| compose    | 21+    | 5       | Optional     | Beta   | It's support for a composable component for HaishinKit.                  |
| lottie     | 21+    | 5       | Optional     | Beta   | It's a module for embedding Lottie animations into live streaming video. |

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

## 🌏 Related projects

| Project name                                                                                    | Notes                                                         | License                                                                                                          |
|-------------------------------------------------------------------------------------------------|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| [HaishinKit for iOS, macOS, tvOS and visionOS.](https://github.com/HaishinKit/HaishinKit.swift) | Camera and Microphone streaming library via RTMP for Android. | [BSD 3-Clause "New" or "Revised" License](https://github.com/HaishinKit/HaishinKit.swift/blob/master/LICENSE.md) |
| [HaishinKit for Flutter.](https://github.com/HaishinKit/HaishinKit.dart)                        | Camera and Microphone streaming library via RTMP for Flutter. | [BSD 3-Clause "New" or "Revised" License](https://github.com/HaishinKit/HaishinKit.dart/blob/master/LICENSE.md)  |

## 📜 License

BSD-3-Clause
