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
        - [ ] VP9
        - [ ] AV1
    - [ ] Playback
        - [x] HEVC(0.13.4)
        - [ ] VP9
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

### [v2](https://github.com/veovera/enhanced-rtmp/blob/main/docs/enhanced/enhanced-rtmp-v2.md)

- [ ] Enhancements to RTMP and FLV
- [ ] Enhancing onMetaData
- [ ] Reconnect Request
- [ ] Enhanced Video
    - [ ] Ingest
        - [ ] VP8
        - [ ] AV1(HDR)
    - [ ] Playback
        - [ ] VP8
        - [ ] AV1(HDR)
- [ ] Multitrack Streaming via Enhanced RTMP
- [ ] Enhancing NetConnection connect Command
