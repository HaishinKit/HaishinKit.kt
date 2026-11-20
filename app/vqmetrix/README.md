# 📊 VQMetrix Example

Camera and MediaProjection sample capture with the standalone
[VQMetrix transfer library](https://github.com/HaishinKit/VQMetrix/tree/main/Android).

## 🚀 Use

Build `:app:assembleDebug`. Both source screens offer **Save sample** from their existing
mixer composition. Preferences → VQMetrix configures duration, codec and bitrates and
lets you share or upload packages. Capture is 15 fps within 640×480, preserving aspect
ratio and recording observed readback times. Source shutdown cancels unfinished sampling;
re-encoding can finish after sampling completes.

Select the Mac through Bonjour and enter its 4-digit connection code. HTTP is for trusted
LAN use and does not encrypt video or codes. The Mac evaluates packages and saves reports.

## 📦 Dependency

The module consumes `com.github.HaishinKit:VQMetrix:1.0.0` from JitPack.
No neighboring VQMetrix checkout is required. For development use
`-PvqmetrixTransferDir=/path/to/VQMetrix/Android/transfer`; a test Maven repository can be
selected with `-PvqmetrixRepository=/path/to/repository`.

## 📚 Scope

UI, capture and replay encoding live here. Upload and Bonjour discovery live in the
external library. HaishinKit library code is unchanged. Each variant records timings,
whole-app CPU time, sampled PSS memory and OS thermal state. These are offline replay
measurements, not hardware utilization or a live-stream stability guarantee.

See the [API reference and guides](https://haishinkit.github.io/VQMetrix/) for details.
