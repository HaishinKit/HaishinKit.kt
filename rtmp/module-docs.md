# Module rtmp

RTMP and RTMPS publishing and playback for HaishinKit.

Use [RtmpConnection][com.haishinkit.rtmp.RtmpConnection] and
[RtmpStream][com.haishinkit.rtmp.RtmpStream] for direct connection and stream control.
For a single-stream session, register
[RtmpStreamSessionFactory][com.haishinkit.rtmp.RtmpStreamSessionFactory] with
[StreamSession.Builder][com.haishinkit.stream.StreamSession.Builder].

An extended RTMP standard called Enhanced RTMP is being developed by the Veovera Software
Organization.
Please check [this repository](https://github.com/veovera/enhanced-rtmp/) for the specifications.

Publishing sends `onMetaData` as an AMF ECMA array. Enhanced video codecs use
numeric FourCC values in `videocodecid` (HEVC, AV1, VP8 and VP9), and Opus uses its
numeric FourCC in `audiocodecid`. H.264 and AAC retain their legacy numeric IDs.

## [Enhanced RTMP v1 support](https://github.com/veovera/enhanced-rtmp/blob/main/docs/enhanced/enhanced-rtmp-v1.md)

| Video codec | Publishing | Playback |
| --- | --- | --- |
| HEVC | ☑️ | ☑️ |
| HEVC Main10 HDR (HLG) | ☑️ | ☑️ |
| VP9 | ☑️ | ☑️ |
| AV1 | ☑️ | ☑️ |

| Other features | Status |
| --- | --- |
| Enhancing onMetaData | ☑️ |
| Extending NetConnection connect Command (`fourCcList`) | ☑️ |
| Metadata Frame | — |

☑️ Supported · — Not implemented. Codec availability also depends on the device.

### HLG composition, publishing and playback

Select `DynamicRangeMode.HLG` when creating the mixer. HLG uses 10-bit BT.2020
surfaces and a floating-point, linear-light OpenGL composition buffer. SDR sRGB images/text and BT.709 video are converted to the working space; SDR reference white is 203 cd/m²
in a nominal 1000 cd/m² HLG output. Existing mixers remain SDR by default.

```kotlin
val mixer = MediaMixer(context, dynamicRangeMode = DynamicRangeMode.HLG)
val camera = Camera2Source(context, dynamicRangeMode = DynamicRangeMode.HLG)
mixer.attachVideo(0, camera).getOrThrow()

val connection = RtmpConnection()
val stream = RtmpStream(context, connection)
stream.videoSetting.profileLevel = VideoCodecProfileLevel.HEVC_MAIN10_4_1
// For AV1: VideoCodecProfileLevel.AV1_MAIN10_4_1
mixer.registerOutput(stream)
mixer.registerOutput(surfaceView) // HkSurfaceView
mixer.startRunning()
// Connect and publish using the normal RtmpConnection / RtmpStream flow.
```

This path requires Android 13 or later, an HLG10 camera for HDR capture, OpenGL ES 3,
10-bit EGL output, floating-point color buffers, `GL_EXT_YUV_target`, and an encoder
that supports Main10 at the selected resolution/rate and HDR/HLG editing from RGB
surfaces. An SDR camera can also be composited into an HLG mixer; it does not become
an HDR capture source. Unsupported camera or encoder settings are rejected.

Use the OpenGL pixel-transform backend and `HkSurfaceView` for HLG display;
`HkTextureView` and the Vulkan backend are not supported by this path. HLG input is currently limited-range BT.2020.
Video sources in an HLG composition use the default effect; other source-video
effects fall back to the default with a warning. Custom effects must not assume the
SDR gamma-encoded working space. `Screen.readPixels` returns `null` for HLG screens;
8-bit bitmap export and HDR-to-SDR tone mapping are not implemented.

For a known HLG incoming stream, select the playback composition range before connecting:

```kotlin
val player = RtmpStream(context, connection, playbackDynamicRangeMode = DynamicRangeMode.HLG)
player.registerOutput(surfaceView) // HkSurfaceView
// Connect and play using the normal flow.
```

The server must preserve the HEVC/AV1 sequence configuration and frame data. HLG
AV1 decoding waits for the first sync frame and validates its in-band sequence
header before configuring the decoder; this avoids starting a 10-bit decoder with
a stale or placeholder 8-bit configuration. Midstream configuration changes still
require a new stream. HLG
color signaling is carried in the codec bitstream; `VideoPacketType.Metadata`
(`colorInfo`), HDR10/PQ, HDR10+ and Dolby Vision are not implemented. Select a new
mixer or playback stream to change dynamic range. The connect command keeps the
`videoFunction` HDR flag unset because that flag also implies `colorInfo` support.
HDR brightness and appearance
require a compatible display and physical-device validation.

### AV1 publishing and playback

AV1 publishing uses Enhanced RTMP (`av01`) and an installed Android `MediaCodec`
encoder. It requires Android 10 or later, an available AV1 encoder, and server support
for Enhanced RTMP AV1. Select a supported AV1 profile before publishing, for example:

```kotlin
stream.videoSetting.profileLevel = VideoCodecProfileLevel.AV1_MAIN8_3_1
```

The encoder must support the requested profile, level, resolution and rate.

AV1 playback uses Enhanced RTMP (`av01`) and the device's built-in Android decoder.
It requires Android 10 or later, an available AV1 decoder, and an Enhanced RTMP server
such as MediaMTX. No additional software codec is bundled. Use the normal
[RtmpStream.play][com.haishinkit.rtmp.RtmpStream.play] API; codec selection follows the received stream.

Playback accepts both AV1 `CodedFrames` and `CodedFramesX` with no composition-time
field. Publishing completes header-only encoder configuration records from the
first keyframe before sending SequenceStart.

AV1 playback waits up to five seconds for the renderer's initial output surface. Changing
to or from AV1, or changing its decoder configuration during playback, is rejected and logged.
Close the stream and create a new playback stream for such changes. Repeated identical
AV1 configuration records are accepted.

### VP8 / VP9 publishing and playback

Enhanced RTMP uses `vp08` for VP8 (v2) and `vp09` for VP9. Both directions use only
Android's installed `MediaCodec` encoders/decoders. No additional codec is bundled.
The server and the peer must support the same Enhanced RTMP codec; VP8 in particular
requires a v2-capable implementation. Support for AV1 or HEVC alone does not imply VP8 support.

Select a profile before starting publication, for example:

```kotlin
stream.videoSetting.profileLevel = VideoCodecProfileLevel.VP8_MAIN
// Or, on Android 7.0+:
stream.videoSetting.profileLevel = VideoCodecProfileLevel.VP9_PROFILE0_31
```

The setting rejects MIME types without an installed encoder. The requested profile,
level, resolution and rate must also be supported by that encoder. Playback selects
the installed decoder from the received FourCC; the connect command advertises only
available decoders. VP playback waits for a keyframe, reads its actual dimensions,
and then waits up to five seconds for the decoder output surface.

The first encoded keyframe supplies the VP configuration fields. Initialization does
not depend on optional encoder CSD. VP8 sends the eight-byte VP configuration record; VP9 sends the version-1 vpcC
fields used by FFmpeg; playback also accepts bare eight-byte and version-prefixed nine-byte VP
configuration records. ISO VP configuration bytes are never queued as Android decoder CSD or frames.
The record's initialization-data length must be zero, as required for VP8/VP9 by the
[VP Codec ISO binding](https://www.webmproject.org/vp9/mp4/).

Codec changes involving VP8, VP9 or AV1, and changes to an active codec configuration,
are rejected. Close playback before making those changes. Identical repeated headers
are accepted. This section does not add SRT/TS support for VP8 or VP9.

## [Enhanced RTMP v2 support](https://github.com/veovera/enhanced-rtmp/blob/main/docs/enhanced/enhanced-rtmp-v2.md)

| Codec | Publishing | Playback |
| --- | --- | --- |
| VP8 (Enhanced Video) | ☑️ | ☑️ |
| AV1 Main10 HDR, HLG (Enhanced Video) | ☑️ | ☑️ |
| Opus, mono/stereo (Enhanced Audio) | ☑️ | ☑️ |

| Other features | Status |
| --- | --- |
| Enhancements to RTMP and FLV | — |
| Enhancing onMetaData | ☑️ |
| Reconnect Request | — |
| Multitrack Streaming via Enhanced RTMP | — |
| Enhancing NetConnection connect Command (`fourCcList`, Opus `audioFourCcInfoMap`) | ☑️ |

☑️ Supported · — Not implemented. Codec availability also depends on the device.

### Connection capabilities

The connect command advertises installed HEVC, VP8, VP9 and AV1 decoders through
`fourCcList`. For Opus, it also sends `audioFourCcInfoMap` with the available
decode/encode flags, and adds `Opus` to `fourCcList` when either capability is available.
`videoFourCcInfoMap` and `capsEx` are not sent; this does not claim support for
reconnection, multitrack streaming or the other optional v2 capabilities.

### Opus publishing and playback

Both directions use the shared [AudioCodec][com.haishinkit.codec.AudioCodec] and
Android's installed `MediaCodec`. No separate RTMP decoder or bundled audio codec is used.
Publishing requires an installed Opus encoder (provided by Android 10+); playback
requires an installed Opus decoder. The connect command advertises the available
Opus encoding/decoding capabilities. The server must support Enhanced RTMP audio.

Select Opus before starting publication, with a matching PCM capture configuration:

```kotlin
stream.audioSetting.mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS
stream.audioSetting.sampleRate = 48_000
stream.audioSetting.channelCount = 2
```

The audio source must provide matching 16-bit PCM. Supported encoder input rates are
8, 12, 16, 24 and 48 kHz; AAC remains the default. Opus publishes `OpusHead` from the
encoder's configuration, followed by compressed packets with the `Opus` FourCC.
Android's encoder CSD wrapper is removed and its codec delay is converted to
48-kHz pre-skip units before publication. Invalid Opus rates/channels or a missing
encoder are rejected synchronously by `publish`, before a request is queued.
`audiocodecid` metadata uses the numeric FourCC value.

Playback uses the normal `RtmpStream.play` flow without receiver encoder settings.
It accepts a single mono/stereo Opus stream with ID-header mapping family zero,
uses a 48-kHz decode clock, and passes ID-header gain and pre-skip to the shared
AudioCodec through Android CSD. Invalid/unsupported headers and coded packets
without an accepted header are ignored. Multitrack,
multichannel mapping, and headerless Enhanced RTMP Opus are not supported.

Repeated identical headers are accepted, and AAC configuration updates are passed
in order to the running decoder. Changing the active audio codec or its Opus
configuration, or starting a new sequence after SequenceEnd, requires closing
playback and starting a new stream; these operations do not replace a running decoder.

## Notice

Enhanced RTMP also requires support on the server side. Please check the support status of the
server you are using.
