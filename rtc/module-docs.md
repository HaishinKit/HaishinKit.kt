# Module rtc

Android 10+ WHIP publishing and WHEP playback using libdatachannel, H.264 and Opus.

Use `RTCSession` directly or register `HTTPStreamSessionFactory` with the common
`StreamSession.Builder`. `RTCStream` integrates the existing MediaMixer and playback
outputs. Set capture PCM to 48 kHz for the default Opus configuration.

The initial implementation uses non-trickle ICE and bounded media queues. It supports
HTTP authentication, SDP exchange, session deletion, RTCP feedback and reconnects.
Adaptive jitter buffering, TWCC/GCC and ICE restart are not included.

The Android 10+ sample app accepts separate WHIP and WHEP endpoints in Preference,
with optional bearer authentication and STUN/TURN URLs. Publish starts WHIP and
Playback starts WHEP. Sample capture and recording use 48 kHz audio.
