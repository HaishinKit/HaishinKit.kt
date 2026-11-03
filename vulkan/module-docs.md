# Module vulkan

Optional Vulkan output backend for Android API 28 and later. Screen composition,
video effects, gravity, and background color use the existing OpenGL renderer;
Vulkan imports its RGBA hardware buffers and presents them to the selected Surface.

Add `implementation(project(":vulkan"))` (or the `com.github.HaishinKit:vulkan`
artifact) and configure the factory **before constructing streams, recorders, or
preview views**:

```kotlin
PixelTransform.factory = VkPixelTransform::create
```

`create` falls back to OpenGL when the native library or required Vulkan device
features are unavailable. To restore the default for future instances:

```kotlin
PixelTransform.factory = PixelTransform::createOpenGL
```

Existing instances retain their backend. The same factory is used by VideoCodec
(RTMP/SRT publishing and recording), HkSurfaceView, HkTextureView, and media outputs.
Screen creation and camera/source composition remain unchanged. Directly owned
transforms should be closed when no longer needed. Setting `surface` or `screen`
to null releases the active Vulkan renderer and its bridge; they can be attached
again later.

Vulkan 1.1, Android hardware-buffer import, foreign queue ownership,
sampler YCbCr conversion, swapchain, and `VK_GOOGLE_display_timing` are required.
The timing extension forwards the composited frame's timestamp to the destination,
including MediaCodec input surfaces. Device-level support cannot guarantee that
every encoder or destination Surface supports Vulkan presentation.

## Building and validation

Initialize dependencies with `git submodule update --init --recursive`. Versions
are pinned by the repository. The module uses NDK 28.2.13676358 and CMake 3.22.1;
Gradle compiles the shaders with the NDK's `glslc` and packages them into the AAR.

```shell
./gradlew :vulkan:assembleDebug :vulkan:assembleRelease :vulkan:lintDebug
./gradlew :haishinkit:testDebugUnitTest
./gradlew :vulkan:connectedDebugAndroidTest
```

The device tests check a solid-color Screen reaching an output Surface after
reattachment, and MediaCodec frames with increasing presentation timestamps.
They skip devices without the required Vulkan features. Also check preview
rotation/resizing, effects, background colors, repeated publish/stop, and RTMP/SRT
A/V synchronization on physical devices before shipping this backend.

Timestamp behavior follows Android's [SurfaceTexture timestamp contract](https://developer.android.com/reference/android/graphics/SurfaceTexture#getTimestamp()).

Host regression tests for fractional display intervals and per-device dispatcher
ownership can run without an Android device:

```shell
cmake -S vulkan/src/test/cpp -B build/vulkan-native-tests
cmake --build build/vulkan-native-tests
ctest --test-dir build/vulkan-native-tests --output-on-failure
```

The Fastlane review lane also runs these tests. Each native renderer owns its
Vulkan dispatcher, including the dispatch references retained by resource deleters.
Out-of-date swapchains recreate their dependent resources; a recording/submission
exception stops the affected renderer instead of reusing incomplete synchronization
state.
