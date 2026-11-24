# 🧪 VP8/VP9 fixtures

These single keyframes were generated from synthetic YUV color bars on a Pixel Tablet
using its OS-provided `c2.exynos.vp8.encoder` and `c2.exynos.vp9.encoder` at 1280×720.
They contain no camera or microphone data. The full twelve-frame sequences were also
decoded with the tablet's built-in decoders during development.

The additional 320×180 VP9 profile-2 fixture was generated from FFmpeg's synthetic
`testsrc2` source with its host libvpx encoder to cover the 10-bit header parser.
It is test data only; no libvpx dependency is added to the Android libraries.
