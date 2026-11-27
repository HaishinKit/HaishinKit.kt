# Module rtc

Android 6.0+ WHIP publishing and WHEP playback using libdatachannel, H.264/VP8/VP9 and Opus.

Use `RtcSession` directly or register `HttpStreamSessionFactory` with the common
`StreamSession.Builder`. `RtcStream` integrates the existing MediaMixer and playback
outputs. Set capture PCM to 48 kHz for the default Opus configuration.

The initial implementation uses non-trickle ICE and bounded media queues. It supports
HTTP authentication, SDP exchange, session deletion, RTCP feedback and reconnects.
Adaptive jitter buffering, TWCC/GCC and ICE restart are not included.

The Android 10+ sample app accepts separate WHIP and WHEP endpoints in Preference,
with optional bearer authentication and STUN/TURN URLs. Publish starts WHIP and
Playback starts WHEP. Sample capture and recording use 48 kHz audio.

`RtcDuplexSession` provides audio-only bidirectional Opus and a reliable text data
channel with application-provided SDP signaling. Its outgoing and incoming streams
use independent codecs. Capture, routing and microphone permission belong to the
host; cancellation releases transport and playback. This backend does not include
libwebrtc software echo cancellation.

`RtcWebSocket` provides single-use WebSocket connections using libdatachannel.
It supports suspending connection/receive, text send, binary receive, bounded
queues, cancellation and explicit close. WSS verifies hostnames against platform
trust anchors or a supplied PEM CA bundle.

## Video codec selection

WHIP uses `stream.videoSetting.profileLevel`: `H264_BASELINE_3_1` (default),
`VP8_MAIN`, or `VP9_PROFILE0_31`. WHEP offers one codec chosen before creating the
session with `RtcConfiguration(playbackVideoCodec = RtcVideoCodec.VP9)` (or `VP8`;
`H264` is the default). The endpoint must accept that codec; there is no automatic
fallback or codec switching during a connection. Close and reconnect to change it.

```kotlin
val publisher = RtcSession(context, Uri.parse(whipUrl))
publisher.stream.videoSetting.profileLevel = VideoCodecProfileLevel.VP8_MAIN

val receiver = RtcSession(
    context,
    Uri.parse(whepUrl),
    StreamSession.Mode.PLAYBACK,
    RtcConfiguration(playbackVideoCodec = RtcVideoCodec.VP8),
)
```

Only OS-provided MediaCodec encoders/decoders are used; no software codec is
bundled. Availability is checked for the session's direction. VP9 is profile 0
(8-bit 4:2:0), one spatial layer; simulcast/SVC are not supported. VP8/VP9 RTP
assembly is bounded and drops incomplete, out-of-order or malformed access units,
requesting a keyframe. The receiver starts from a keyframe and can reconfigure its
surface decoder when keyframe dimensions change. H.264 behavior remains unchanged.

In the sample's Publish controls, HTTP(S) endpoints now offer H.264, VP8 and VP9,
with unavailable device encoders disabled. Audio remains Opus. WHEP playback defaults to
H.264; applications select VP8/VP9 via the session configuration above.

VP receivers advertise conservative `max-fs`/`max-fr` values derived from the
preferred OS decoder's size-dependent capabilities (capped at 8,160 macroblocks
and 30 fps). These are compatibility limits, not a benchmark or the device maximum.
WHIP rejects settings above explicitly signaled server limits; absent limit
parameters remain accepted for compatibility with existing servers. Corrupt VP
keyframe headers are dropped with a recovery keyframe request while audio continues.
