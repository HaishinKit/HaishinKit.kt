# Vulkan rendering

Select both factories **before** creating mixers, streams, recorders, or preview views:

```kotlin
if (Build.VERSION.SDK_INT >= 28 && VkPixelTransform.isSupported()) {
    Screen.factory = VkScreen::create
    PixelTransform.factory = VkPixelTransform::create
} else {
    Screen.factory = Screen::createOpenGL
    PixelTransform.factory = PixelTransform::createOpenGL
}
```

The example application's renderer preference configures both factories. Existing screens and
outputs retain their backend; stop them before switching. Passing an OpenGL Screen to
VkPixelTransform fails explicitly. There is no hidden OpenGL bridge or automatic shader fallback.

## Rendering path

Camera, decoder and MediaProjection surfaces feed an NDK ImageReader configured for GPU sampling.
Vulkan imports its HardwareBuffers with the supplied YCbCr conversion and crop rectangle. Images
and text use Android bitmaps; text rasterization still uses StaticLayout/Canvas on the CPU.
Some ImageReader implementations report crop dimensions rebased to (0, 0), losing a nonzero source origin.
Such producers must supply `VideoSource.videoCropRect`; `VideoCodecSource` obtains it from the
decoder output format, converting inclusive codec bounds to exclusive coordinates.
The Vulkan render pass blends the objects into an RGBA composition image. A second Vulkan pass
samples that image with bicubic B-spline filtering (four linear samples) into the MediaCodec or
preview surface. No EGL context, SurfaceTexture or OpenGL producer is created by this path.

Layout, input acquisition and synchronous GPU transactions run on a common worker. An input image
is retained until every output has finished sampling it. Each output has its own Vulkan device and
composition target, so simultaneous preview and encoding currently repeat composition; this trades
sharing complexity for explicit lifetimes. No mipmaps or CPU video readback are used. Performance,
thermals and A/V synchronization must be measured on target devices.

Screen.readPixels performs an explicit Vulkan transfer to CPU memory. Non-ARGB_8888 bitmap inputs
are converted once per bitmap generation. Remove/dispose screens and close outputs to release
resources. VkScreen.onError reports asynchronous frame failures (default: Android error log).

## Custom effects

OpenGL GLSL and reflected OpenGL uniforms are not automatically converted. Register a Vulkan
implementation for the effect class. The factory is evaluated each frame, so it can read changing
parameters. Built-in default, bilinear, bicubic, monochrome, sepia, mosaic and Lanczos effects already
have Vulkan implementations. Registrations override the built-in implementation for that class.

```kotlin
class TintEffect(var red: Float = 1f) : VideoEffect {
    override val name = "tint"
}

VulkanEffectRegistry.register(TintEffect::class.java) { effect ->
    VulkanShader(
        fragmentAsset = "shaders/example-tint.frag.spv",
        parameters = floatArrayOf(effect.red, 1f, 1f),
    )
}
objectToDraw.videoEffect = TintEffect(0.5f)
// Also usable as PixelTransform.videoEffect.
```

The shipped example-tint.frag demonstrates a custom fragment shader. Compile GLSL 450 to SPIR-V
with the NDK's glslc at build time, and package the files as application assets. The Vulkan module's
CompileVulkanShaders task demonstrates this build integration. VulkanShader accepts explicit asset
paths; malformed/missing assets fail rather than loading a default shader. Unregister with
VulkanEffectRegistry.unregister(TintEffect::class.java).

Shader interface:

- Set 0, binding 0: one combined sampler2D (immutable sampler, including source YCbCr conversion).
- Vertex output / fragment input location 0: vec2 texture coordinates.
- Fragment output location 0: vec4 premultiplied RGBA.
- Push constants (available in both stages), exactly 128 bytes:
  - offset 0: mat4 mvpMatrix (column major);
  - offset 64: vec4 textureRect (normalized left, top, right, bottom of the source crop);
  - offset 80: vec4 parameters[3], up to twelve user floats, zero padded.
- The default scene.vert generates a four-vertex triangle strip from gl_VertexIndex. A custom
  vertex shader may use the same interface; no vertex attributes are bound. Apply textureRect when
  generating coordinates so source cropping remains correct.

Use normalized texture sampling operations compatible with YCbCr converted images when effects
operate on video inputs. Custom descriptor sets, extra textures, storage buffers and arbitrary
uniform layouts are outside this initial API. Shader authors are responsible for matching the
interface above and device-supported shader capabilities.

## Validation

Unit tests cover effect registration, parameter binding, explicit unsupported-effect failures,
factory selection, decoder crop metadata and frame pacing. Instrumentation tests cover Vulkan-only bitmap/text/alpha
composition, custom SPIR-V and changing constants, RGBA/YUV HardwareBuffer input and nonzero source cropping, bicubic output,
surface reattachment and increasing MediaCodec presentation timestamps. Instrumentation tests
require a Vulkan-capable Android device; compiling their APK is not a rendering test.

Device validation (2026-11-09): all eight Vulkan instrumentation tests passed on Pixel 5a / Android 14,
with zero skips. Synthetic RGBA/YUV inputs were used; no camera capture was performed. Real capture
and playback, renderer switching, long-running preview/encoding performance and A/V sync remain
unverified.
