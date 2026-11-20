# 📊 VQMetrix Example

Optional UI for the [VQMetrix](https://github.com/HaishinKit/VQMetrix) Android library.
Place VQMetrix beside HaishinKit.kt, or set `-PvqmetrixDir=/path/to/VQMetrix/Android/producer`.

## 🚀 Use

Build `:app:assembleDebug`. Camera Publish and MediaProjection offer **Save sample**
from their existing mixer composition. Preferences → VQMetrix configures duration,
codec and bitrates and lets you share or upload saved packages. Capture is limited
to 15 fps within 640×480; it preserves aspect ratio and records observed readback times.
The source must keep running while samples are acquired. Source shutdown cancels an
unfinished capture; re-encoding can finish after capture completes.

## 📚 Scope

UI and lifecycle hooks belong to this Example. Capture, exact-frame replay through
Android MediaCodec, ZIP packaging, upload and Bonjour discovery belong to VQMetrix.
No HaishinKit library changes are required. The feature is omitted without VQMetrix.

This evaluates re-encoding of sampled composition, rather than the original live
stream or network delivery. See VQMetrix's transfer and user guides for details.
HTTP is limited to trusted local networks; video and tokens are unencrypted over HTTP.
The macOS upload receiver is not implemented yet.
