# Asset playback fixtures

`asset-short.mp4` is a 1.2-second, video-only stream-copy excerpt from the sample
clip reused from HaishinKit.swift's `Examples/macOS/SampleVideo_360x240_5mb.mp4`.
`asset-rotated.mp4` is a generated red/green/blue/yellow quadrant clip with a
90-degree display matrix, allowing pixel assertions for rotation direction.
`asset-colors.mp4` is the unrotated clip; `asset-180.mp4` and `asset-270.mp4`
carry the corresponding display matrices. FFmpeg positive display rotation is
counterclockwise (Android extracts the corresponding clockwise rotation key).
They exercise OS decoding, loop timing, rotation and pixel rendering without
camera, microphone or network input.

```sh
ffmpeg -i sample_video.mp4 -t 1.2 -map 0:v:0 -an -c:v copy asset-short.mp4
ffmpeg -f lavfi -i 'color=c=red:s=64x48:r=15:d=1.2,drawbox=x=32:y=0:w=32:h=24:color=0x00ff00:t=fill,drawbox=x=0:y=24:w=32:h=24:color=blue:t=fill,drawbox=x=32:y=24:w=32:h=24:color=yellow:t=fill' -c:v libx264 -pix_fmt yuv420p asset-colors.mp4
ffmpeg -display_rotation 90 -i asset-colors.mp4 -c copy asset-rotated.mp4
ffmpeg -display_rotation 180 -i asset-colors.mp4 -c copy asset-180.mp4
ffmpeg -display_rotation 270 -i asset-colors.mp4 -c copy asset-270.mp4
```
