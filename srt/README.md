# Android SRT build foundation

This module builds libsrt 1.5.7 with Mbed TLS 3.6.5 encryption support and
packages it in `libsrt.so`, loaded with `System.loadLibrary("srt")`. MPEG-TS muxing/demuxing and public HaishinKit
stream/session APIs are not implemented yet.

## Requirements

- Android Studio with JDK 17 or newer compatible with the root Gradle version.
- Android SDK platform 37 (the same platform used by the other modules).
- NDK `28.2.13676358` and CMake `3.22.1`, installable from SDK Manager.
- `local.properties` containing `sdk.dir` for your Android SDK.
- Internet access for the first build. CMake downloads pinned official source
  archives and verifies their SHA-256 hashes. Downloads stay in `.cxx`.

On macOS, Android Studio's bundled JDK can be selected with:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

From the repository root:

```sh
./gradlew :srt:assembleDebug :srt:assembleRelease :app:assembleDebug
```

The AARs are written to `srt/build/outputs/aar/`. The sample app depends on
`:srt`, so its APK includes the native runtime. Supported ABIs are `arm64-v8a`,
`armeabi-v7a`, `x86_64`, and `x86`. Native libraries use 16 KB page alignment.

## Logger

`SrtLogger` follows Swift's singleton logger: it sets libsrt's process-wide level
and manages changes to selected functional areas. Its native methods live on
`SrtLogger`.

```kotlin
SrtLogger.level = SrtLogger.Level.WARNING
SrtLogger.functionalAreas = setOf(SrtLogger.FunctionalArea.CONNECTION)
```

The initial level is `NOTICE`. The area set records areas managed by this logger,
not all native defaults: newly selected areas are enabled and removed areas are
disabled. Settings apply to every socket. Configuration updates are serialized,
and the exposed area set is a defensive snapshot. This configures libsrt logging;
it does not install a custom log handler.

## Logger tests

With an Android device or emulator connected, run the Logger tests:

```sh
./gradlew :srt:connectedDebugAndroidTest
```

The Logger tests also run on a macOS JVM using a host build of the same JNI code.
With `ANDROID_HOME` pointing to the SDK and `JAVA_HOME` set as above:

```sh
mkdir -p srt/build/host-jni
cp "$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/include/jni.h" srt/build/host-jni/
"$ANDROID_HOME/cmake/3.22.1/bin/cmake" -S srt/src/main/cpp -B srt/build/host -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ANDROID_HOME/cmake/3.22.1/bin/ninja" \
  -DSRT_JNI_INCLUDE_DIR="$PWD/srt/build/host-jni" -DCMAKE_BUILD_TYPE=Release
"$ANDROID_HOME/cmake/3.22.1/bin/cmake" --build srt/build/host --target srt srt_logger_test --parallel 6
"$ANDROID_HOME/cmake/3.22.1/bin/ctest" --test-dir srt/build/host --output-on-failure
./gradlew :srt:testDebugUnitTest -PsrtNativeLibraryDir="$PWD/srt/build/host"
```

Without a host JNI library, local JVM Logger tests are skipped. Android tests
always use the native libraries packaged in the test APK.
The host C++ logger test verifies actual native filtering by severity and area.

## Third-party sources

- [libsrt](https://github.com/Haivision/srt/tree/v1.5.7), MPL-2.0.
- [Mbed TLS](https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.5),
  Apache-2.0 OR GPL-2.0-or-later (use under Apache-2.0).

Upstream license texts are included in the AAR under
`assets/libsrt/`. Dependency versions and checksums are in
`src/main/cpp/CMakeLists.txt`. Unmodified libsrt sources are available from the
versioned upstream link above.
