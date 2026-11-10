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
Image elements use `source` with a base64 data URI, and video elements use `track`.

Custom types require platform-specific support. Android preserves an unknown type's place with
`NullScreenObject`, whereas Swift's built-in factory returns `nil` for unsupported types.
Only properties represented by the snapshot are shared; effects, video scaling, and orientation
are not encoded by this format.

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
