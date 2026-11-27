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
