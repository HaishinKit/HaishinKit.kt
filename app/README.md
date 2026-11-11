# Android sample app

The sample demonstrates camera publishing, playback, recording, screen sharing,
and screen composition. It requires **Android 10 (API 29) or later** so it can
include the optional libdatachannel-based RTC module. The core and RTMP/SRT
libraries retain their API 23 minimum.

## Build and run

Open the repository in Android Studio and run the `app` configuration on a device.
Grant camera and microphone permissions for camera publishing. For command-line
builds, configure `JAVA_HOME` and the Android SDK/NDK versions used by Gradle:

```sh
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The first native build downloads the pinned transport dependencies. A WHIP/WHEP
server and endpoints for the stream you want to send or receive are required for
network testing. The sample does not run a streaming server.

## WHIP publishing and WHEP playback

In the **Preference** tab, set the following fields:

| Field | Value |
|---|---|
| Publish URL (RTMP / SRT / WHIP) | The complete WHIP `http://` or `https://` endpoint, including its path and query parameters. HTTP(S) selects WebRTC. |
| WHEP playback URL | The complete WHEP endpoint for the stream to watch. Required for WebRTC playback. |
| Bearer token (optional) | The token without the `Bearer ` prefix. Leave empty when authentication is not needed. Used for both endpoints. |
| STUN / TURN servers (optional) | One libdatachannel-compatible ICE server URL per line, for example `stun:stun.example.com:3478`. Configure TURN if your network requires it. |

**RTMP StreamName** is disabled for HTTP(S) and is not appended to either URL.
Use the endpoints provided by your server; WHEP paths are not inferred from WHIP.
Settings are retained in memory for the current app process, including credentials.
Reopen the corresponding tab after editing settings to create a session with the
updated URLs, token, and ICE servers.

For publishing:

1. Open **Publish** and grant camera/microphone permissions.
2. Choose a camera if necessary, then tap **GO LIVE**.
3. Tap **STOP** to disconnect or cancel an in-progress connection.

For playback:

1. Open **Playback** and tap **PLAY**.
2. Tap **STOP** to disconnect or cancel an in-progress connection.

Keep Publish open and use another device/player to test continuous WHIP publishing
and WHEP playback together. Leaving the Publish tab stops its camera/microphone
capture. Playback errors are displayed on screen; publishing errors appear as
messages. An authentication failure is reported with the HTTP status code.

The sample allows plain HTTP for local testing and uses Android's normal HTTPS
certificate validation. Use reachable server addresses when testing on a physical
device: `127.0.0.1` refers to that device, not the development computer. On Android
versions requiring local network permission, use **Allow local network** in Preference.

Outgoing WebRTC media is H.264 Baseline level 3.1 and Opus. The sample captures
48 kHz PCM and applies the same rate to its recording and publishing encoders.
Read the [RTC module guide](../rtc/README.md) for supported signaling, transport
limitations and library-level usage.

## RTMP and SRT

For RTMP/RTMPS, enter the base stream URL and **RTMP StreamName**. For SRT, enter
the full `srt://` URI including options such as `mode`, `streamid`, and `latency`;
RTMP StreamName is ignored. Publish and Playback select the session's media
mode. The sample continues to use AAC for RTMP and SRT at a 48 kHz sample rate.

See the [SRT guide](../srt/README.md) for socket modes and encryption options.

## Screen sharing and renderer selection

**MediaProjection** uses the same publishing endpoint, authentication and ICE
settings as the Publish tab. Grant the Android screen-capture prompt to start it.
**Apply renderer** in Preference stops active outputs and reopens the app screens.
The existing OpenGL and optional Vulkan backends are available.

## Validation

```sh
./gradlew :app:testDebugUnitTest :app:ktlintCheck
./gradlew :app:connectedDebugAndroidTest
```

The sample's device test enters WHIP/WHEP settings and opens the publishing and
playback screens without contacting an external server. Transport and real codec
round-trip tests live in the [RTC module](../rtc/README.md#building-and-validation).
