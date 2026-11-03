# Android SRT

This module builds libsrt 1.5.7 with Mbed TLS 3.6.5 encryption support and
packages it in `libsrt.so`, loaded with `System.loadLibrary("srt")`. Internal `SrtSocket` supports live-mode
connections and message transport. Public `SrtConnection` owns one peer connection;
`SrtStream` publishes or plays one MPEG-TS program through the existing media pipeline.

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
Debug and Release AARs retain debug symbols in `libsrt.so`; Release uses
`RelWithDebInfo`. The sample APK also retains them. Consuming apps should set
`android.packaging.jniLibs.keepDebugSymbols += "**/libsrt.so"` to retain the
symbols when packaging their own APKs.

## Public connection and stream APIs

For the common session API, register `SrtStreamSessionFactory`:

```kotlin
StreamSession.Builder.registerFactory(SrtStreamSessionFactory)
val session = StreamSession.Builder(context, Uri.parse("srt://192.168.1.10:9710"))
    .setMode(StreamSession.Mode.PUBLISH) // or PLAYBACK
    .build()
// Register session.stream as a mixer output for publishing, or attach a view for playback.
session.connect().onFailure { error -> /* show connection/startup error */ }
// On shutdown:
session.close()
```

The factory owns transport/media monitoring. Closing also cancels pending
listener setup, and the session can reconnect. Async media failure or peer closure
returns the session state to CLOSED. Connect cancellation propagates to its caller.

The Example app registers both RTMP and SRT factories. In Settings, enter a complete
`srt://host:port` URL, including query options; the RTMP stream-name field is ignored.
For listener mode use `srt://:9710?mode=listener`. Return to Camera to publish or
Playback to receive. These screens refresh their session after a URL change.
The sender and receiver must use complementary caller/listener socket modes;
either socket mode can publish or play. Settings are held in memory for this run.

```kotlin
val connection = SrtConnection(scope)
connection.connect("srt://example.com:9710?streamid=live")
val stream = SrtStream(context, connection)
// Register stream as a MediaMixer output and configure audio/video settings.
stream.expectedMedias = setOf(MediaType.AUDIO, MediaType.VIDEO)
stream.publish()
// For reception, attach a StreamView output and call stream.play() instead.
// On shutdown:
stream.dispose()
connection.close()
```

The supplied scope must contain a Job; cancellation closes its transport and media
jobs. Caller, listener (`?mode=listener`) and rendezvous modes are supported.
Listener accepts one peer. State and asynchronous stream failures use StateFlow.
Only one active stream may use a connection. Do not mix raw I/O with stream I/O.
`stream.close()` stops media and retains the connection; `connection.close()` ends
the transport. Close the stream before reconnecting.

The TS pipeline supports H.264/H.265 and AAC, with mono/stereo audio playback.
PAT/PMT, PCR, PTS/DTS, continuity, discontinuity and in-band format changes are
handled. Queues and PES assemblies are bounded; overflow terminates the stream.
Stream-send cancellation retains whole TS packets and keeps the transport open;
the next publisher signals discontinuity. Raw I/O cancellation closes the socket.

## Internal socket API

The socket and JNI APIs are internal. Within this module, a caller can send data as follows:

```kotlin
SrtSocket(SrtSocket.Options(latencyMs = 120)).use { socket ->
    socket.connect(resolvedRemoteAddress)
    socket.send(data)
}
```

`listen(local)` and suspending `accept()` implement listener mode. For rendezvous,
use `connect(remote, local, rendezvous = true)`. Accepted sockets have independent
lifetimes and must be closed by their owner. Resolved IPv4 and IPv6 addresses are
supported; scoped IPv6 addresses are currently rejected. SrtConnection handles
DNS resolution and URL parsing.

Send calls split data into messages of up to 1316 bytes. They suspend when the
native send buffer is full and do not introduce an unbounded queue. Callers must
not mutate the supplied array until send returns. Receive calls return one owned
byte array per message. Send and receive can run concurrently. Native send ownership
keeps chunks from different send calls in order. One coroutine owns accept/receive
or an entire `inputs` collection; competing readers are rejected immediately.
Repeated connect calls are rejected using native socket state. Ending input
collection closes the socket.

The implementation follows the native-class structure used by the Vulkan module:
`SRT/SrtSocket.h` and `.cpp` own configuration, socket state, send fragmentation,
reader/send ownership, statistics and cleanup. `srt-jni.cpp` converts JVM values
and translates C++ exceptions. Opaque context IDs hold shared native owners so
close cannot free an object still used by another JNI call; IDs are never reused.
The C++ mutex only protects short nonblocking operations.

Kotlin is a Coroutine/Flow adapter with no socket-state locks. Suspending
operations run in the caller's coroutine. The socket does not create its own scope
or detached jobs: the owner uses `coroutineScope` and `launch` to manage
send/receive lifetimes. Native sends process at most 32 messages per call to allow
cancellation checks between bursts.

A shared C++ `SrtEventLoop` waits in `srt_epoll_wait`. Read/accept waits subscribe
to IN, and connection/send waits subscribe to OUT plus error notifications.
Wait registrations are one-shot, and callbacks run after their IN/OUT interest
is disabled. A lifetime epoll subscription preserves readiness across gaps between
waits, including connections that arrive before accept starts waiting. Idle ERR
notifications are edge-triggered to avoid spinning; OUT is enabled only while an
operation needs it. A nonblocking socket pair wakes the loop for updates
and shutdown, so Kotlin does not poll or use retry delays.

JNI retains each continuation callback with a global reference and attaches the
native callback thread to the JVM only when needed. Callbacks run outside native
locks. Cancellation unregisters its wait token; readiness/close/cancellation races
complete a registration at most once. Queued senders wait for native send ownership
notifications rather than OUT events. Shutdown wakes and joins the shared thread
before releasing epoll and cleaning up libsrt.

Cancelling an active connect, accept, send, or receive closes
that socket and rethrows cancellation. Connect has a configurable deadline;
callers can use `withTimeout` around other operations. Native failures preserve
the SRT error code and rejection reason. Close is idempotent and immediate, with
linger disabled; queued packets are not guaranteed to be delivered on close.
Statistics are snapshots and do not reset the native measurement interval.

## Logger

`SrtLogger` follows Swift's singleton logger: it sets libsrt's process-wide level
and manages changes to selected functional areas. Its native methods live on
`SrtLogger`; socket native methods live on `SrtSocket`.

```kotlin
SrtLogger.level = SrtLogger.Level.WARNING
SrtLogger.functionalAreas = setOf(SrtLogger.FunctionalArea.CONNECTION)
```

The initial level is `NOTICE`. The area set records areas managed by this logger,
not all native defaults: newly selected areas are enabled and removed areas are
disabled. Settings apply to every socket. Configuration updates are serialized,
and the exposed area set is a defensive snapshot. This configures libsrt logging;
it does not install a custom log handler.

## Socket tests

With an Android device or emulator connected, run the JNI and loopback tests:

```sh
./gradlew :srt:connectedDebugAndroidTest
```

The socket tests also run on a macOS JVM using a host build of the same JNI code.
With `ANDROID_HOME` pointing to the SDK and `JAVA_HOME` set as above:

```sh
mkdir -p srt/build/host-jni
cp "$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/include/jni.h" srt/build/host-jni/
"$ANDROID_HOME/cmake/3.22.1/bin/cmake" -S srt/src/main/cpp -B srt/build/host -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ANDROID_HOME/cmake/3.22.1/bin/ninja" \
  -DSRT_JNI_INCLUDE_DIR="$PWD/srt/build/host-jni" -DCMAKE_BUILD_TYPE=RelWithDebInfo
"$ANDROID_HOME/cmake/3.22.1/bin/cmake" --build srt/build/host --target srt srt_logger_test srt_event_loop_test --parallel 6
"$ANDROID_HOME/cmake/3.22.1/bin/ctest" --test-dir srt/build/host --output-on-failure
./gradlew :srt:testDebugUnitTest -PsrtNativeLibraryDir="$PWD/srt/build/host"
```

Without a host JNI library, local JVM socket tests are skipped. Android tests
always use the native libraries packaged in the test APK.
The host C++ tests verify native log filtering and epoll readiness, cancellation,
close and shutdown. JVM tests exercise the same code through JNI, including
concurrent connections and cancellation of queued senders.

## MPEG-TS verification

Tests reuse unchanged HaishinKit.swift TS fixtures, including stereo AAC and video
resolution changes. Origins and checksums are in `src/test/resources/ts/README.md`.
Remux tests compare encoded media payloads. TSReader reuses per-track PES storage
and reads packets directly; TSWriter copies slices into one final wire buffer.
For the same 100-frame, 32 KB video mux/demux benchmark, measured JVM allocation
fell from 343,810,976 to 7,763,992 bytes (about 97.7%). The performance test reports
timing without a timing assertion. Android device codec/rendering validation is
still required; host tests cover transport and TS processing.

## Third-party sources

- [libsrt](https://github.com/Haivision/srt/tree/v1.5.7), MPL-2.0.
- [Mbed TLS](https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.5),
  Apache-2.0 OR GPL-2.0-or-later (use under Apache-2.0).

Upstream license texts are included in the AAR under
`assets/libsrt/`. Dependency versions and checksums are in
`src/main/cpp/CMakeLists.txt`. Unmodified libsrt sources are available from the
versioned upstream link above.
