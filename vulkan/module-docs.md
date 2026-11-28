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
