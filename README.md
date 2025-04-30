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

## 💬 Communication

* GitHub Issues and Discussions are open spaces for communication among users and are available to
  everyone as long
  as [the code of conduct](https://github.com/HaishinKit/HaishinKit.swift?tab=coc-ov-file) is
  followed.
* Whether someone is a contributor to HaishinKit is mainly determined by their GitHub profile icon.
  If you are using the default icon, there is a chance your input might be overlooked, so please
  consider setting a custom one. It could be a picture of your pet, for example. Personally, I like
  cats.
* If you want to support e-mail based communication without GitHub.
    * Consulting fee is [$50](https://www.paypal.me/shogo4405/50USD)/1 incident. I'm able to
      response a few days.

## 🌏 Related projects

 Project name                                                                                    | Notes                                                         | License                                                                                                          
-------------------------------------------------------------------------------------------------|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------
 [HaishinKit for iOS, macOS, tvOS and visionOS.](https://github.com/HaishinKit/HaishinKit.swift) | Camera and Microphone streaming library via RTMP for Android. | [BSD 3-Clause "New" or "Revised" License](https://github.com/HaishinKit/HaishinKit.swift/blob/master/LICENSE.md) 
 [HaishinKit for Flutter.](https://github.com/HaishinKit/HaishinKit.dart)                        | Camera and Microphone streaming library via RTMP for Flutter. | [BSD 3-Clause "New" or "Revised" License](https://github.com/HaishinKit/HaishinKit.dart/blob/master/LICENSE.md)  

## 🎨 Features

### RTMP

- [x] Authentication
- [x] Publish
- [x] Playback
- [ ] Action Message Format
    - [x] AMF0
    - [ ] ~~AMF3~~
- [ ] ~~SharedObject~~
- [x] RTMPS
    - [x] Native (RTMP over SSL/TSL)
- [ ] [Enhanced RTMP (Working in progress)](https://github.com/HaishinKit/HaishinKit.kt/wiki/Supports-Enhanced-RTMP-Status)
    - [ ] v1
    - [ ] v2
- [x] Audio Codecs
    - [x] AAC
- [x] Video Codecs
    - [x] H264, HEVC

### ⏺️ Recording

Now support local recording. Additionally, you can specify separate videoSettings and audioSettings
from the live stream.

```kt
val recorder: StreamRecorder by lazy { StreamRecorder(requireContext()) }
recorder.videoSettings.profileLevel = VideoCodecProfileLevel.HEVC_MAIN_3_1
recorder.attachStream(stream)
recorder.startRecording(
    File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "output.mp4"
    ).toString(),
    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
)
```

### Filter

- [Table Of Filters](https://docs.haishinkit.com/kt/latest/haishinkit/com.haishinkit.graphics.effect/index.html)

### Sources

- [x] Single camera with Camera2 api
- [x] Multi camera with Camera2 api
- [x] MediaProjection
- [x] Microphone with AudioRecord api.

### View rendering

|    -     |     HkSurfaceView      |       HkTextureView       |
|:--------:|:----------------------:|:-------------------------:|
|  Engine  |      SurfaceView       |        TextureView        |
| Playback |          beta          |           beta            |
| Publish  |        ✅ Stable        |         ✅ Stable          |
|   Note   | Recommend Android 7.0+ | Recommend Android 5.0-6.0 |

### Others

- [x] Hardware acceleration for H264 video encoding/AAC audio encoding.
    - [x] Asynchronously processing.
- [x] Graphics api
    - [x] ✅ OpenGL
    - [ ] 🐛 Vulkan

### Settings

```kt
stream.audioSettings.bitrate = 32 * 1000

stream.videoSettings.width = 640 // The width resoulution of video output.
stream.videoSettings.height = 360 // The height resoulution of video output.
stream.videoSettings.bitrate = 160 * 1000 // The bitRate of video output.
stream.videoSettings.IFrameInterval = 2 // The key-frmae interval
```

### Offscreen Rendering.

Through off-screen rendering capabilities, it is possible to display any text or bitmap on a video
during broadcasting or viewing. This allows for various applications such as watermarking and time
display.

<p align="center">
  <img width="732" alt="" src="https://github.com/HaishinKit/HaishinKit.kt/assets/810189/f2e189eb-d98a-41b4-9b4c-0b7d70637675">
</p>

```kt
mixer.attachVideo(cameraSource)

val text = TextScreenObject()
text.textSize = 60f
text.textValue = "23:44:56"
text.layoutMargins.set(0, 0, 16, 16)
text.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
text.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM
stream.screen.addChild(text)

val image = ImageScreenObject()
image.bitmap = BitmapFactory.decodeResource(resources, R.drawable.game_jikkyou)
image.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM
image.frame.set(0, 0, 180, 180)
stream.screen.addChild(image)
```

## 🌏 Architecture Overview

### Publishing Feature

<p align="center">
  <img width="732" alt="" src="https://user-images.githubusercontent.com/810189/164874912-3cdc0dde-2cfb-4c94-9404-eeb2ff6091ac.png">
</p>

## 🐾 Examples

Examples project are available for Android.

- [x] Camera and microphone publish.
- [x] RTMP Playback

```sh
git clone https://github.com/HaishinKit/HaishinKit.kt.git
cd HaishinKit.kt
git submodule update --init

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
  implementation 'com.github.HaishinKit.HaishinKit~kt:compose:x.x.x'
  implementation 'com.github.HaishinKit.HaishinKit~kt:lottie:x.x.x'
}
```

### Dependencies

| -          | minSdk | Android | Requirements | Status | Description                                                              |
|:-----------|:-------|:--------|:-------------|:-------|:-------------------------------------------------------------------------|
| haishinkit | 21+    | 5       | Require      | Stable | It's the base module for HaishinKit.                                     |
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

### RTMP Usage

Real Time Messaging Protocol (RTMP).

### Filter API (v0.1)

```
- [assets]
  - [shaders]
    - custom-shader.vert(optional)
    - custom-shader.frag
```

```kt
package my.custom.filter

import com.haishinkit.graphics.filter.VideoEffect

class Monochrome2VideoEffect(
    override val name: String = "custom-shader"
) : VideoEffect
```

```kt
mixer.screen.videoEffect = Monochrome2VideoEffect()
```

### Related Project

* HaishinKit.swift - Camera and Microphone streaming library via RTMP, HLS for iOS, macOS and tvOS.
    * https://github.com/HaishinKit/HaishinKit.swift

## 📜 License

BSD-3-Clause
