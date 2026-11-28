# Synthetic audio mixing fixture

`audio-mixing.mp4` is generated test data: a blue 64x48 video with a 440 Hz left
channel and a 660 Hz right channel, AAC at 44.1 kHz, lasting 1.2 seconds.
It contains no third-party media. Generate with:

```sh
ffmpeg -f lavfi -i 'color=c=blue:s=64x48:r=15:d=1.2' -f lavfi -i 'aevalsrc=0.125*sin(2*PI*440*t)|0.125*sin(2*PI*660*t):s=44100:d=1.2' -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 128k -t 1.2 -movflags +faststart audio-mixing.mp4
```

`audio-surround.m4a` is generated 5.1 AAC test audio with six sine waves, used to
verify that the OS decoder honors the stereo output limit before native mixing:

```sh
ffmpeg -f lavfi -i 'aevalsrc=0.05*sin(2*PI*440*t)|0.05*sin(2*PI*660*t)|0.05*sin(2*PI*880*t)|0.05*sin(2*PI*100*t)|0.05*sin(2*PI*550*t)|0.05*sin(2*PI*770*t):s=48000:d=1.2:c=5.1' -c:a aac -b:a 256k audio-surround.m4a
```
