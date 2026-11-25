# Module rtmp

RTMP and RTMPS publishing and playback for HaishinKit.

Use [RtmpConnection][com.haishinkit.rtmp.RtmpConnection] and
[RtmpStream][com.haishinkit.rtmp.RtmpStream] for direct connection and stream control.
For a single-stream session, register
[RtmpStreamSessionFactory][com.haishinkit.rtmp.RtmpStreamSessionFactory] with
[StreamSession.Builder][com.haishinkit.stream.StreamSession.Builder].

## Enhanced RTMP support

An extended RTMP standard called Enhanced RTMP is being developed by the Veovera Software
Organization.
Please check [this repository](https://github.com/veovera/enhanced-rtmp/) for the specifications.

- Enhanced RTMP also requires support on the server side. Please check the support status of the
  server you are using.

### [v1](https://github.com/veovera/enhanced-rtmp/blob/main/docs/enhanced/enhanced-rtmp-v1.md)

- [ ] Enhancing onMetaData
- [ ] Defining Additional Video Codecs
    - [ ] Ingest
        - [x] HEVC(0.13.4)
        - [x] VP9
        - [ ] AV1
    - [ ] Playback
        - [x] HEVC(0.13.4)
        - [x] VP9
        - [x] AV1
- [ ] Extending NetConnection connect Command
- [ ] Metadata Frame

### AV1 playback

AV1 playback uses Enhanced RTMP (`av01`) and the device's built-in Android decoder.
It requires Android 10 or later, an available AV1 decoder, and an Enhanced RTMP server
such as MediaMTX. No additional software codec is bundled. Use the normal
[RtmpStream.play][com.haishinkit.rtmp.RtmpStream.play] API; codec selection follows the received stream.
AV1 publishing is not implemented by this change.

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
are accepted. This does not add SRT/TS codec support or a new HDR publishing pipeline.

### [v2](https://github.com/veovera/enhanced-rtmp/blob/main/docs/enhanced/enhanced-rtmp-v2.md)

- [ ] Enhancements to RTMP and FLV
- [ ] Enhancing onMetaData
- [ ] Reconnect Request
- [ ] Enhanced Video
    - [ ] Ingest
        - [x] VP8
        - [ ] AV1(HDR)
    - [ ] Playback
        - [x] VP8
        - [ ] AV1(HDR)
- [ ] Multitrack Streaming via Enhanced RTMP
- [ ] Enhanced Audio
    - [x] Opus publishing (mono/stereo)
    - [x] Opus playback (mono/stereo)
- [ ] Enhancing NetConnection connect Command

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

Repeated identical headers are accepted. Changing the active audio codec or its
configuration, or starting a new sequence after SequenceEnd, requires closing
playback and starting a new stream; these operations do not replace a running decoder.
