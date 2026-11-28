# Module vulkan

Experimental native Vulkan rendering for HaishinKit on Android 9 (API 28) and later.
Use this optional backend for screen composition, camera/decoder inputs, video effects,
previews, recording, and encoded stream output. OpenGL is the default backend.

## Installation

Add the Vulkan module alongside your existing core and transport dependencies, using
JitPack and the **same release version for every HaishinKit module**:

```kotlin
dependencies {
    val haishinkitVersion = "0.20.6"
    implementation("com.github.HaishinKit.HaishinKit~kt:vulkan:$haishinkitVersion")
}
```

The module requires API 28, Vulkan 1.1, Android HardwareBuffer import, foreign queue
ownership, sampler YCbCr conversion, swapchain support, and `VK_GOOGLE_display_timing`.
Android's API level alone does not establish Vulkan support. Check the device at
runtime with `VkPixelTransform.isSupported()`.

## Select both factories

Configure **both** factories in application startup, before creating any `MediaMixer`,
stream, recorder, or preview view:

```kotlin
import android.os.Build
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.screen.Screen
import com.haishinkit.vulkan.VkPixelTransform
import com.haishinkit.vulkan.VkScreen

fun configureVideoRenderer() {
    if (Build.VERSION.SDK_INT >= 28 && VkPixelTransform.isSupported()) {
        Screen.factory = VkScreen::create
        PixelTransform.factory = VkPixelTransform::create
    } else {
        Screen.factory = Screen::createOpenGL
        PixelTransform.factory = PixelTransform::createOpenGL
    }
}
```

`Screen.factory` chooses composition; `PixelTransform.factory` chooses the output
renderer used by video codecs and preview views. Selecting only one creates a mixed
backend: `VkPixelTransform` rejects an OpenGL `Screen`.

Existing objects keep their backend. To switch, stop publishing/playback/recording
and screen sharing, dispose the old mixers/screens/outputs, set both factories, and
create the objects again. Restore OpenGL with the two `createOpenGL` factories above.
`VkPixelTransform.create()` does **not** choose OpenGL automatically when Vulkan is
unsupported; the application makes that choice before constructing objects.

## Rendering and lifetime

Camera, decoder, and MediaProjection surfaces feed an NDK ImageReader. Vulkan imports
their HardwareBuffers, applies YCbCr conversion and cropping, and composes video,
images, text, effects, and background color into an RGBA image. A second Vulkan pass
resamples that image into the preview or MediaCodec input surface. This path does not
use an OpenGL/EGL bridge or CPU video readback. Text still uses Android CPU bitmap
rasterization, and `Screen.readPixels` explicitly transfers the composition to CPU
memory for snapshots.

The default output filter is bicubic. `videoGravity`, `backgroundColor`, `frameRate`,
and `videoEffect` remain available through `PixelTransform`. Each output currently
has its own Vulkan device and composition target; preview and encoding therefore
repeat composition. Measure performance, thermal behavior, and A/V synchronization
on the intended devices rather than assuming this backend is faster than OpenGL.

Close directly owned transforms and dispose screens when finished. Detaching a
transform's `surface` releases its output renderer; detaching its `screen` unregisters
it from that composition. You can attach them again before closing the transform.
`VkScreen.onError` reports asynchronous frame failures; its default handler logs them.
A device passing `isSupported()` can still reject a particular destination Surface
or encoder input surface, so test both preview and the intended encoding output.

## Effects and custom shaders

Default, bilinear, bicubic, monochrome, sepia, mosaic, and Lanczos effects have Vulkan
implementations. An OpenGL GLSL shader is not converted automatically. Register a
SPIR-V implementation of a custom `VideoEffect` with `VulkanEffectRegistry`:

```kotlin
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.vulkan.VulkanEffectRegistry
import com.haishinkit.vulkan.VulkanShader

class TintEffect(var red: Float = 1f) : VideoEffect {
    override val name = "tint"
}

fun registerTintEffect() {
    VulkanEffectRegistry.register(TintEffect::class.java) { effect ->
        VulkanShader(
            fragmentAsset = "shaders/example-tint.frag.spv",
            parameters = floatArrayOf(effect.red, 1f, 1f),
        )
    }
}
```

Call `registerTintEffect()` before assigning `TintEffect(0.5f)` to a screen object's or transform's `videoEffect`. The factory
runs each frame, allowing parameters to change. The module includes the tint shader
asset shown above. For your own shader, compile GLSL 450 with the NDK's `glslc` and
package the SPIR-V file as an Android asset. Unregistered effects, missing assets,
and invalid shaders fail explicitly rather than silently falling back to OpenGL.

`VulkanShader` accepts up to twelve finite float parameters. The shader interface uses
one combined sampler at set 0/binding 0 and a 128-byte push-constant block: the MVP
matrix, source crop rectangle, and three `vec4` parameter slots. Sampling must respect
the source crop, including every filter tap. See the module's shader sources and
[the shader contract](https://github.com/HaishinKit/HaishinKit.kt/blob/0.20.6/vulkan/README.md#custom-effects) for the full interface and sampling helper.

## Device diagnostics

`inspectDevices()` enumerates device information without creating a renderer or
output surface:

```kotlin
val deviceInfoJson = VkPixelTransform.inspectDevices()
```

The JSON `devices` array contains device names/types, API and driver versions, vendor
and device IDs, and extensions. An absent native library or Vulkan loader yields an
empty array; Vulkan initialization/enumeration errors throw `IllegalStateException`.
Enumeration is diagnostic information, not the backend compatibility check: use
`isSupported()` to select the backend.

## MediaCodec throughput benchmark

This measures the normal **paced composition → output Surface → hardware H.264 encoder**
pipeline. It is not an unpaced MediaCodec capacity test: a result near the requested
frame rate means that the pipeline kept up, not that the encoder cannot run faster.

The 2026-11-28 measurement uses a Pixel 8, Android 17 DP11, a 60 Hz display, and
Release builds of both the core and Vulkan libraries in an instrumentation APK
(without whole-application R8 optimization). Both backends select the same
hardware encoder by name and request identical codec settings. The source is SDR,
four 128×128 gradient/checkerboard bitmaps filling the composition, plus a moving
translucent bitmap. Composition and encoded output have the same dimensions, with
the default bicubic output effect and no camera, decoder input, preview, audio,
network transport, or file muxer.

Each condition runs three times, alternating backend order and rotating scenario
order. Each fresh encoder/renderer has a 3-second warmup, a 10-second measurement,
and a 2-second pause after cleanup and image validation. The encoder requests
8/16/40 Mbps for 720p/1080p/2160p, CBR, a 1-second keyframe interval, no B frames,
real-time priority, and an operating rate equal to the target frame rate.

The benchmark waits for composition initialization before attaching the encoder.
It decodes one measured keyframe **after** the timing window and checks dimensions
and luminance variation, so an empty/uniform output cannot count as a successful
trial. This is a content sanity check, not a visual-quality score.

### Recorded results

Encoder: `c2.exynos.h264.encoder`. The recorded battery temperature ranges from
30.2 to 31.2 °C; Android thermal status is 0 at every measurement start/end. All
recorded display-rate readings are 60 Hz. No configurations were skipped.
The [24 raw trials](https://github.com/HaishinKit/HaishinKit.kt/blob/dc5bbe059f56a4b1edc601723b456782f1285cd8/vulkan/benchmarks/2026-11-28-pixel8.json)
include the OS fingerprint and benchmark source revision `d8e7b721`.

| Composition / output | Target fps | OpenGL fps, median (min–max) | Vulkan fps, median (min–max) |
|---|---:|---:|---:|
| 1280×720 | 60 | 60.00 (59.90–60.00) | 46.00 (45.40–46.70) |
| 1920×1080 | 30 | 30.00 (29.90–30.00) | 30.00 (30.00–30.00) |
| 1920×1080 | 60 | 59.90 (59.80–60.10) | 43.00 (42.20–45.30) |
| 3840×2160 | 60 | 57.50 (57.40–57.50) | 31.00 (30.69–32.70) |

| Composition / target | OpenGL app CPU ms/s | Vulkan app CPU ms/s | OpenGL p95 output gap, ms | Vulkan p95 output gap, ms |
|---|---:|---:|---:|---:|
| 1280×720 / 60 fps | 552 | 787 | 22.2 | 26.1 |
| 1920×1080 / 30 fps | 390 | 669 | 37.9 | 43.0 |
| 1920×1080 / 60 fps | 567 | 782 | 21.2 | 27.3 |
| 3840×2160 / 60 fps | 542 | 731 | 19.8 | 36.8 |

On this device and scene, both backends keep up at 1080p/30 fps. OpenGL produces
more encoded frames in the 60 fps cases and uses less app CPU. Neither backend
fully reaches 4K/60 fps. These measurements do not isolate whether rendering,
synchronization, pacing, or codec input handling is the limiting stage.

The tables use the median of the three independent trials; the fps ranges are the
minimum and maximum, not confidence intervals. CPU is process CPU time divided by
wall time: 1,000 ms/s is one fully occupied CPU core. It includes app rendering and
draining, excludes CPU used by separate codec/system services, and is not GPU time.
The p95 gap measures time between dequeued encoded output buffers, including pacing
and scheduling; it is not encoder processing latency. The raw data also contains
output bitrate, presentation timestamp gaps, temperature, thermal status, output
format, and decoded-picture checks. Target-fps shortfall cannot identify whether a
frame was skipped by rendering, pacing, or the encoder.
Arrival-window boundaries can differ by one frame; a small value above the target
does not establish additional sustained capacity.

These are one-device, synthetic-scene measurements on a development Android build.
The platform-reported encoder format differs between Surface producers, including
color transfer/range, despite the same requested settings; this is not a comparison
of identical encoded pixels. Requested CBR also does not establish that the driver
emits a padded constant bitrate. No quality, camera-import, decode-to-encode,
latency-under-load, battery-power, or sustained thermal-capacity comparison is made.

### Reproduce

From the repository root, initialize submodules and build the opt-in Release test
variant. Normal instrumentation runs skip the benchmark unless explicitly enabled.
Use a Vulkan-capable, unlocked ARM64 device on API 29+ held at 60 Hz; keep
power/background-load conditions stable.
Set `ANDROID_SERIAL` when multiple devices are attached.

```shell
git submodule update --init --recursive
./gradlew :vulkan:assembleReleaseAndroidTest \
  --init-script vulkan/benchmarks/release.init.gradle \
  -Pandroid.injected.build.abi=arm64-v8a
adb install -r -t vulkan/build/intermediates/apk/androidTest/release/vulkan-release-androidTest.apk
adb shell am instrument -w -r \
  -e class com.haishinkit.vulkan.RendererCodecBenchmarkTest#comparePacedEncoderThroughput \
  -e rendererBenchmark true -e benchmarkRepetitions 3 \
  -e benchmarkSeconds 10 -e benchmarkWarmupSeconds 3 \
  -e benchmarkRevision "$(git rev-parse HEAD)" \
  com.haishinkit.vulkan.test/androidx.test.runner.AndroidJUnitRunner > benchmark.log
ruby -rjson -e 'line = File.readlines(ARGV[0]).find { |s| s.start_with?("INSTRUMENTATION_STATUS: benchmark_document=") }; abort "No complete result" unless line; puts JSON.pretty_generate(JSON.parse(line.split("benchmark_document=", 2)[1]))' benchmark.log > benchmark.json
ruby vulkan/benchmarks/summarize.rb benchmark.json
```

Check the instrumentation result for success before publishing results. Unsupported
encoder size/rate/CBR combinations are recorded as skipped. The summary rejects
incomplete trials, non-Release builds, unexpected decoded pictures, non-increasing
presentation timestamps, and non-60 Hz readings at the measurement boundaries.
The test's `exportLastResult` method with `-e rendererBenchmarkExport true` can export
the saved JSON after an interrupted run; check its source revision because that
file may be incomplete or retained from a previous run.

## 30 fps camera frame intervals

Average fps can hide alternating short and long intervals. This separate experiment
uses the Pixel 8's rear camera at 1920×1080 with Camera2 `TEMPLATE_RECORD` and a fixed
AE target range of `[30, 30]`. Capture results verify that range for every recorded
frame. A test-only `VideoSource` feeds the normal OpenGL SurfaceTexture or Vulkan
HardwareBuffer path, then one encoder Surface. Both backends request 1080p/30 fps,
H.264 at 16 Mbps, bicubic output resampling, and the same encoder settings as above.
The source uses sensor coordinates, without a camera-orientation rotation.

Each backend runs four 30-second windows after a 5-second warmup, alternating order
and closing the camera/renderer between trials. A minimal foreground activity keeps
camera access active and requests a 60 Hz display. The scene and ambient light are
not calibrated; exposure and sensor frame duration are included in the raw data.
The results cover one device and Android development build, not all camera hardware.

### Measurement points

- **Camera sensor:** `CaptureResult.SENSOR_TIMESTAMP`; camera callback arrival is
  recorded independently. Only differences within this sensor clock are used.
- **Surface call start/return:** immediately before/after `eglSwapBuffers` on OpenGL
  or `vkQueuePresentKHR` on Vulkan. These are the observable producer-side input
  boundaries. [Surface input](https://developer.android.com/reference/android/media/MediaCodec)
  does not expose a dequeueable MediaCodec input buffer; API return is not the time
  the encoder hardware consumed the frame or completed waiting on GPU work.
- **Output received:** immediately after synchronous `dequeueOutputBuffer` returns
  encoded data on a dedicated draining thread, using a 5 ms wait timeout. The wait
  returns when data is available; it is not a fixed 5 ms polling cadence.
- **Output PTS:** `BufferInfo.presentationTimeUs`, treated as a media timeline rather
  than a delivery timestamp. Surface-assigned PTS values are also recorded for
  checking input/output correspondence, with ±1 µs rounding tolerance.

Application observation times use
[`elapsedRealtimeNanos`](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtimeNanos())
and native `CLOCK_BOOTTIME`. The trace labels carry those explicit numeric times;
analysis does not depend on the ftrace display clock. Sensor and PTS timestamps are
not subtracted from these times to claim a capture/encoding latency.
The marker is emitted after API return and carries the start/end values; the
marker's own short trace span is not the swap/present call duration.
The `HK.Input.GL` / `HK.Input.VK` app trace events are emitted only while app tracing
is enabled. Their overhead is included in this profiled experiment. The previous
synthetic throughput experiment was untraced and uses a different workload.

### Recorded interval distributions

Recorded on 2026-11-28 with benchmark source `3b3216d2`, encoder
`c2.exynos.h264.encoder`, and Release libraries. The reported battery temperature
is 30.2 °C and Android thermal status is 0 at every measurement boundary. All
display readings are 60 Hz. Applied capture ranges are `[30, 30]`; every one of the
7,190 measured encoded outputs matches a recorded input PTS within ±1 µs. There
are no gaps in the recorded capture-result frame numbers or non-increasing output
PTS values. Exposure is automatic and varies across trials; per-trial mean exposure
ranges from 19.71 to 32.66 ms.
See the [readable per-trial statistics](https://github.com/HaishinKit/HaishinKit.kt/blob/3b1f5ef0148dbc25707fde383ac30caaf9cb99a0/vulkan/benchmarks/2026-11-28-pixel8-camera-intervals.summary.json)
and [compressed numeric timing samples](https://github.com/HaishinKit/HaishinKit.kt/blob/3b1f5ef0148dbc25707fde383ac30caaf9cb99a0/vulkan/benchmarks/2026-11-28-pixel8-camera-intervals.json.gz).
`camera_interval_summary.rb` accepts either JSON or gzip-compressed JSON.

| Stage / backend | p50 ms | p95 ms | p99 ms, median (min–max) | Worst interval ms |
|---|---:|---:|---:|---:|
| Camera sensor / OpenGL | 33.38 | 33.55 | 33.73 (33.65–33.78) | 38.23 |
| Camera sensor / Vulkan | 33.44 | 33.56 | 33.73 (33.69–33.85) | 37.12 |
| Camera callback / OpenGL | 33.14 | 48.71 | 59.41 (55.39–62.47) | 73.89 |
| Camera callback / Vulkan | 33.11 | 48.97 | 59.53 (58.01–61.82) | 74.27 |
| Surface call start / OpenGL | 33.36 | 37.97 | 45.47 (44.24–51.68) | 59.99 |
| Surface call start / Vulkan | 33.33 | 39.62 | 45.56 (44.23–46.67) | 57.12 |
| Surface call return / OpenGL | 33.37 | 40.15 | 48.18 (46.34–51.81) | 57.62 |
| Surface call return / Vulkan | 33.33 | 40.00 | 46.63 (45.02–47.33) | 58.13 |
| Encoded output received / OpenGL | 33.28 | 47.88 | 58.69 (53.38–60.71) | 70.24 |
| Encoded output received / Vulkan | 33.19 | 47.10 | 54.14 (51.63–58.28) | 71.24 |
| Encoded output PTS / OpenGL | 33.34 | 33.36 | 33.38 (33.32–33.39) | 50.08 |
| Encoded output PTS / Vulkan | 33.34 | 33.35 | 33.36 (33.35–33.38) | 50.01 |

| Stage / backend | <20 ms % | Within 33.333 ±5 ms % | >45 ms % | >66.667 ms % |
|---|---:|---:|---:|---:|
| Surface call return / OpenGL | 1.06 | 83.07 | 1.98 | 0.00 |
| Surface call return / Vulkan | 1.20 | 82.17 | 1.61 | 0.00 |
| Encoded output received / OpenGL | 4.66 | 59.12 | 7.14 | 0.08 |
| Encoded output received / Vulkan | 4.45 | 52.98 | 7.29 | 0.08 |

| Backend | Camera fps | Surface return fps | Output fps | Surface API p99 ms | Worst Surface API ms |
|---|---:|---:|---:|---:|---:|
| OpenGL | 29.97 | 29.92 | 29.92 | 12.55 | 19.06 |
| Vulkan | 29.93 | 30.00 | 30.00 | 6.28 | 17.76 |

Quantiles are nearest-rank quantiles within each trial. The table reports the median
of the four per-trial p50/p95/p99 values and the range of their p99 values; the worst
interval is the maximum across every trial, not the median maximum. These are not
confidence intervals. The threshold percentages pool the observed intervals across
trials. The criteria are not a partition: `>66.667 ms` is also included in `>45 ms`.
The raw records include standard deviation, RMSE from 33.333 ms, complete histograms,
camera callback timing, requested/applied capture range, and Surface API duration.
Events are selected by observation time; only intervals between two events inside
the same window enter the statistics. Sensor intervals use the sensor timestamps
of the selected capture-result callbacks. Boundary-spanning intervals and setup/
teardown are excluded.

Both paths average approximately 30 encoded frames/s, while Surface return and
output-receive intervals vary substantially. The output PTS p99 remains close to
33.333 ms, with occasional gaps up to about 50 ms; the actual output-receive p99 is
54.14–58.69 ms and the worst receive interval is about 71 ms. Camera metadata
callback delivery also varies despite much steadier sensor timestamps.
Vulkan has a lower median p99 in this recording, while OpenGL has more output
intervals within ±5 ms of the nominal period. Trial p99 ranges overlap, and the
scene is uncontrolled and automatic exposure varies between trials. These data do not establish that
either backend is universally steadier or that MediaCodec execution alone causes
the observed variation.

This separates visible cadence at submission and delivery from PTS regularity. It
does not isolate GPU completion, hardware encoding, driver buffering, and the drain
thread's scheduling from each other. It also does not identify which captured image
was sampled by the renderer, so it cannot count repeated camera images from PTS alone.
The persisted camera records contain numeric timing/settings rather than images.

### Reproduce the camera comparison

Build/install the Release instrumentation APK as above, then explicitly grant camera
access to the test package and enable **app-only** tracing. Always stop tracing after
the run, including on failure. The benchmark normally takes about five minutes.

```shell
adb shell pm grant com.haishinkit.vulkan.test android.permission.CAMERA
adb shell atrace --async_start -b 8192 -a com.haishinkit.vulkan.test
adb shell am instrument -w -r \
  -e class com.haishinkit.vulkan.CameraCodecIntervalBenchmarkTest#compareCameraIntervals \
  -e cameraIntervalBenchmark true -e cameraBenchmarkRepetitions 4 \
  -e cameraBenchmarkSeconds 30 -e cameraBenchmarkWarmupSeconds 5 \
  -e benchmarkRevision "$(git rev-parse HEAD)" \
  com.haishinkit.vulkan.test/androidx.test.runner.AndroidJUnitRunner > camera.log
adb shell atrace --async_stop > camera.atrace
ruby vulkan/benchmarks/camera_interval_stats.rb camera.log camera.atrace camera.json
ruby vulkan/benchmarks/camera_interval_summary.rb camera.json
ruby vulkan/benchmarks/test_camera_interval_stats.rb
```

To summarize the recorded archive directly:

```shell
ruby vulkan/benchmarks/camera_interval_summary.rb vulkan/benchmarks/2026-11-28-pixel8-camera-intervals.json.gz
```

The analyzer requires a successful complete recording, Release builds, all expected
trials, applied fixed `[30, 30]` camera settings, successful Surface submissions,
60 Hz readings, and a trace buffer that has not wrapped. It preserves the numeric
input/capture/output samples for checking the distribution and PTS matches.
The camera test's `exportLastResult` with `-e cameraIntervalExport true` can export
the saved recording; source revision and completeness must be checked on recovery.

## Example and development checks

The example app requires Android 10 (API 29) or later. In **Settings → Renderer**,
select **OpenGL** or **Vulkan**, then **Apply renderer**. The action stops existing
outputs and rebuilds the screens. Vulkan is disabled when the device check fails.

For a source checkout, initialize the pinned native dependencies before building:

```shell
git submodule update --init --recursive
./gradlew :vulkan:assembleRelease :vulkan:lintDebug
./gradlew :vulkan:connectedDebugAndroidTest
```

Native host tests cover frame pacing, dispatch ownership, surface transforms, and
render synchronization. Device tests cover composition, effects, HardwareBuffer
inputs, cropping, surface reattachment, and encoder presentation timestamps. Building
an instrumentation APK does not run those checks. Also verify real capture/playback,
repeated start/stop, output resize/rotation, and A/V synchronization on target devices.
