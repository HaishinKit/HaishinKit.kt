# Module haishinkit

Core APIs for capturing, composing, encoding, playing, and recording media.

Use [MediaMixer][com.haishinkit.media.MediaMixer] to attach audio and video sources and route
media to streams, preview views, or a [MediaRecorder][com.haishinkit.media.MediaRecorder].
Build video compositions with [Screen][com.haishinkit.screen.Screen] and its screen objects.
[StreamSession][com.haishinkit.stream.StreamSession] provides a common publishing and playback
interface; register a protocol-specific factory before building a session.

## Screen snapshot compatibility

[ScreenObjectSnapshot][com.haishinkit.screen.scene.ScreenObjectSnapshot] shares its JSON
fields with HaishinKit.swift for `container`, `video`, `text`, and `image`. A `screen` snapshot
is restored as a container. Sizes and margins use integer values, alignment uses 0/1/2
(left/center/right or top/middle/bottom), and children retain their order.

Text elements use `value`, `fontSize`, and `color` (`#AARRGGBB`). The default font size is 32,
with a bold system font. Text supports explicit newlines and wrapping within its requested
bounds. Layout preserves the requested size in the snapshot. Font metrics, glyphs, and line
breaks can vary between Android and Apple system fonts; pixel-identical text is not guaranteed.
Image elements use `source` with a base64 data URI or local file URI, and video elements use `track`.
Video gravity is encoded as `resize`, `resizeAspect`, or `resizeAspectFill`. SceneFile
normalizes an omitted gravity to `resizeAspect`. Legacy snapshot/SceneManager restoration
keeps its original fill default; newly captured video snapshots encode gravity explicitly.

Custom types require platform-specific support. Unsupported objects are invisible and preserve
original snapshots, including visibility intent and subtrees, when captured again. Android's
`NullScreenObject` retains Apple's unsupported `asset` video-file objects this way.

## Portable scene files

[SceneFile][com.haishinkit.screen.scene.SceneFile] reads and writes `.scene` ZIP
files shared with Apple platforms. The ZIP contains camelCase `scene.json` (a snapshot without
an envelope), `manifest.json` (`{"format":"haishinkit.scene","version":1}`), and flat
`media/<filename>` entries. STORE and DEFLATE archives, including Java data descriptors, are
supported; standalone snapshot JSON is accepted without external media.

```kotlin
val archive = SceneFile(snapshot, mapOf("overlay.png" to imageBytes))
val bytes = archive.write()
val restored = SceneFile.read(bytes)
```

Use `media/<filename>` in image/asset `elements["source"]`. Matching local file URLs are
normalized when resources are supplied by basename. After reading, copy resources into app
storage and resolve known media references to local file URIs before constructing screen
objects. Preserve opaque objects and all their resources during edits. Archives do not grant
device permissions or restore network credentials. Resource names cannot contain directories
or traversal and must be unique under a conservative portable case key
(NFC, lowercase, uppercase, lowercase, NFC with ROOT/default Unicode casing); limits are 256 files, 128 MiB of resources, and 4 MiB of JSON.

## Editing screen objects

[ScreenCoordinateTransform][com.haishinkit.screen.ScreenCoordinateTransform] maps between
composition pixels and preview-local pixels for `RESIZE`, `RESIZE_ASPECT`, and
`RESIZE_ASPECT_FILL`. Supply the preview's actual pixel size and matching gravity, and recreate
the transform when the preview or composition changes size. Letterbox coordinates are not clamped.

[ScreenObjectEditor][com.haishinkit.screen.ScreenObjectEditor] provides hit testing, movement,
and proportional scaling for editable direct children of a composition, without a Compose dependency.
Supply eligible objects in drawing order (back to front), excluding background video. Register and
unregister objects alongside scene changes; the editor does not manage rendering resources.

```kotlin
val editor = ScreenObjectEditor(mixer.screen, listOf(image, text))
val coordinates = ScreenCoordinateTransform(
    Size(1280, 720), Size(preview.width, preview.height), VideoGravity.RESIZE_ASPECT_FILL,
)
val point = coordinates.toScreen(PointF(event.x, event.y))
val target = point?.let { editor.hitTest(it) }
if (point != null && target != null) {
    editor.transform(target, anchor = point, scale = 1.1f)
    val selectionBounds = editor.getBounds(target)?.let { coordinates.toPreview(it) }
}
```

Use `moveTo` for top-left positioning, or `transform` with separate anchor and destination points
to combine scaling and panning. Geometry retains fractional precision between input events,
resolves alignment into a left/top frame, and clamps position and proportional size to the
composition; text font size scales with its frame. After external layout changes, call
`invalidateBounds` so editing uses the newly rendered geometry. Keep editor calls on one thread,
as with direct frame changes. Gesture detection, focus state, dialogs, and selection decorations
belong to the UI layer; the camera example demonstrates these with Compose.

## Camera torch and zoom

Keep a `Camera2Source` for each camera to control its torch and zoom independently:

```kotlin
val camera = Camera2Source(context, cameraId).apply {
    isTorchEnabled = true
    zoomRatio = 2f
}
mixer.attachVideo(0, camera).getOrThrow()
// After the camera has opened:
val torchSupported = camera.isTorchSupported
val zoomRange = camera.zoomRatioRange
camera.zoomRatio = zoomRange.clamp(3f)
```

Settings are retained before opening and applied together to the first repeating request,
including after session recreation. Unsupported torch requests are ignored. Zoom must be
finite and positive; the applied ratio is clamped to the opened camera's range while the
property retains the requested value. Before opening, capabilities report no torch and 1x.
Android 11+ uses zoom-ratio control where available; other cameras use a centered sensor crop.
The legacy `MediaMixer.isToucheEnabled` setter updates currently attached cameras together;
attaching a source preserves that source's own torch setting.

## HLG video composition

Create `MediaMixer(context, dynamicRangeMode = DynamicRangeMode.HLG)` before attaching
sources to opt into a 10-bit BT.2020 HLG pipeline. The default remains SDR. The OpenGL
renderer uses floating-point linear-light composition, converts SDR assets into that
space, and renders HLG to 10-bit encoder or display surfaces. Use `HkSurfaceView` for
preview; `HkTextureView`, Vulkan, HDR bitmap export and HDR-to-SDR tone mapping are
not supported by this path.

HLG requires Android 13 or later and compatible graphics, display and codec hardware.
`Camera2Source(context, dynamicRangeMode = DynamicRangeMode.HLG)` requests HLG10
capture and rejects unsupported cameras. Encoding requires an HDR/HLG-editing capable
Main10 HEVC or AV1 encoder. Configure a new mixer or playback stream to change range.
See the RTMP module's Enhanced RTMP guide for publishing/playback configuration.

## Local video assets

`AssetScreenObject` renders a local video's first video track using Android's
`MediaExtractor` and a device-provided `MediaCodec` decoder. No additional codec
library is bundled. File, content and `android.resource` URIs are supported;
audio, network playback, DRM and HDR assets are not supported by this object.
SDR assets can be included in an HLG composition through the existing SDR conversion.

```kotlin
val clip = AssetScreenObject(context).apply {
    frame = android.graphics.Rect(40, 40, 400, 280)
    isLooping = true
    onError = { error -> /* Marshal to the UI thread if needed. */ }
    startReading(videoUri)
}
mixer.screen.addChild(clip)
// Removing an actively playing object stops its decoder; reattaching restarts it.
mixer.screen.removeChild(clip)
clip.close()
```

`videoGravity` controls fit/fill, and file rotation metadata is applied without
following device rotation. Cropping and non-square pixel aspect ratios are used
when fitting the video. `currentTimeUs` reports the current pass's last frame;
looping starts a new pass at zero. `cancelReading()` stops playback but retains the
source URI for serialization. After cancellation or non-looping EOF, call
`startReading()` explicitly to play again. `close()` permanently closes this instance. Loading
and decoding are asynchronous; `onError` may run on the playback or renderer thread.
Rendering supports both OpenGL and the optional Vulkan backend. Each attached
asset uses its own decoder and renderer-owned surface, independent of capture tracks.

Use `ScreenObjectFactory(context)` to recreate video assets from scene snapshots.
Without a context, asset nodes remain opaque and are retained for future use.
Custom `asset` creators may override video reconstruction, for example for Lottie.
Scene archives must package the movie bytes under `media/` and reference that path;
resolve packaged paths to local URIs before calling the factory. Content permissions
and playback positions are not part of a portable scene.
