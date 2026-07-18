# Upscale Relay — Android client

A native Kotlin / Jetpack Compose client for the upscale relay server. It
plays videos from the server's library or from local storage: encoded video
is sent to the GPU server, upscaled through an ONNX model, returned as a
lossless or bandwidth-capped HEVC stream, and rendered with hardware decode
in perfect sync with the file's original audio and subtitles. See
[`docs/ANDROID_CLIENT.md`](docs/ANDROID_CLIENT.md) for the full plan
and current status.

## Architecture

```text
server library ──► server_file session ─┐
                                        ├─► framed TCP downlink ─► bounded
local file ─► MediaExtractor demux ─►   │     byte queue ─► localhost TCP ─►
              framed TCP uplink ────────┘     libmpv ─► MediaCodec ─► Surface
```

Original audio/subtitles attach from a Range-capable HTTP source (the
server's `/media` URL, or an on-device loopback bridge for local files) with
absolute-PTS synchronization; seeks open a new *epoch* on the same sockets.

Modules:

- `app` — Compose UI (adaptive tablet/phone shells, player, settings),
  playback ViewModel, MediaSession + foreground playback service,
  Picture-in-Picture, mDNS discovery, diagnostics.
- `relay-protocol` — pure-Kotlin protocol v1 framing and JSON models.
- `relay-client` — control WebSocket, session state machine, downlink
  receiver, uplink sender, bounded media queue, localhost media server,
  reconnect policy and failure taxonomy.
- `player-mpv` — lifecycle-checked libmpv wrapper and `SurfaceView` host.
- `relay-demux` — SAF browsing, MediaExtractor access-unit source, and the
  local HTTP bridge for repeated document access.

Android plays the hardware-decoded HEVC tiers only (true lossless plus the
lossy bandwidth ladder). FFV1 is intentionally desktop-only: it has no
hardware decoder and would burn battery in software.

## Building

Prerequisites: JDK 17, Android SDK 37, and a physical arm64 device.
The Gradle wrapper JAR and mpv native binaries are not committed; both
bootstrap paths are version- and SHA-256-pinned.

```powershell
cd android_client
.\bootstrap-wrapper.ps1
.\native\fetch_mpv_native.ps1
.\gradlew.bat :relay-protocol:test :relay-client:test :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest  # physical device attached
```

```bash
cd android_client
./bootstrap-wrapper.sh
./native/fetch_mpv_native.sh
./gradlew :relay-protocol:test :relay-client:test :app:assembleDebug
```

For an auditable native build instead of extracting the pinned upstream APK,
run `native/build_mpv_from_source.sh` on Linux/macOS (upstream does not
support building on Windows or WSL). Install and launch with:

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.upscalerelay.android/.MainActivity
```

## Server requirements

- A running [upscale-relay](https://github.com/brpjerry/upscale-relay) server with at least one model
  (`relay-server --models-dir models --ep tensorrt`); `--library` is only
  needed for the server-library source. The server advertises itself over
  mDNS (`_upscalerelay._tcp`) for the app's discovery list.
- A trusted LAN. Protocol v1 is cleartext with no authentication; do not
  expose ports 8590/8591 to untrusted networks.

## Libraries

Kotlin coroutines, Jetpack Compose (Material 3 + extended icons), AndroidX
lifecycle/DataStore, kotlinx.serialization, and OkHttp. The JNI callback
surface is adapted from mpv-android commit `3018d47`; the prebuilt path
extracts the arm64 libraries (libmpv + FFmpeg) from its 2026-04-25 release
APK, SHA-256
`4400bcba6be9cec1128e24d1eba153d8727384926b0639fa7fe44d4e36b04f81`. See
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) before distributing an
APK.

## Diagnostics

Settings → Player → "Save diagnostic log to Documents" writes a timestamped
session log to `Documents/UpscaleRelay/` (session events, mpv output,
periodic playback telemetry, crash stacks; the newest ten files are kept).
A machine-readable telemetry snapshot is also written each second to the
app-private `files/phase4-latest.json`.

## More documentation

- [`DEVELOPMENT.md`](DEVELOPMENT.md) — feature status, verification
  workflows, buffering/failure internals, and telemetry details.
- [`docs/ANDROID_CLIENT.md`](docs/ANDROID_CLIENT.md) — phase plan,
  implementation status, acceptance gates.
- [`docs/ANDROID_DEVICE_NOTES.md`](docs/ANDROID_DEVICE_NOTES.md) —
  physical-device validation record.
