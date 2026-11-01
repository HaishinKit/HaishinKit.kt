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
