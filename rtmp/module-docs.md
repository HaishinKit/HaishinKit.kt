# Module rtmp.

This is a module for RTMP communication.

## Supports Enhanced RTMP Status

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
        - [ ] AV1
- [ ] Extending NetConnection connect Command
- [ ] Metadata Frame

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