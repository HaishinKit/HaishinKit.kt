# Module rtc

Android 6.0+ WHIP publishing and WHEP playback using libdatachannel, H.264 and Opus.

Use `RTCSession` directly or register `HTTPStreamSessionFactory` with the common
`StreamSession.Builder`. `RTCStream` integrates the existing MediaMixer and playback
outputs. Set capture PCM to 48 kHz for the default Opus configuration.

The initial implementation uses non-trickle ICE and bounded media queues. It supports
HTTP authentication, SDP exchange, session deletion, RTCP feedback and reconnects.
Adaptive jitter buffering, TWCC/GCC and ICE restart are not included.

The Android 10+ sample app accepts separate WHIP and WHEP endpoints in Preference,
with optional bearer authentication and STUN/TURN URLs. Publish starts WHIP and
Playback starts WHEP. Sample capture and recording use 48 kHz audio.

`RTCDuplexSession` provides audio-only bidirectional Opus and a reliable text data
channel with application-provided SDP signaling. Its outgoing and incoming streams
use independent codecs. Capture, routing and microphone permission belong to the
host; cancellation releases transport and playback. This backend does not include
libwebrtc software echo cancellation.

`RTCWebSocket` provides single-use WebSocket connections using libdatachannel.
It supports suspending connection/receive, text send, binary receive, bounded
queues, cancellation and explicit close. WSS verifies hostnames against platform
trust anchors or a supplied PEM CA bundle.
