# Android client — development notes

Working notes for developing and verifying the client. The README covers
architecture, building, and libraries; the phase plan and acceptance gates
live in [`docs/ANDROID_CLIENT.md`](docs/ANDROID_CLIENT.md) and the
physical-device validation record in
[`docs/ANDROID_DEVICE_NOTES.md`](docs/ANDROID_DEVICE_NOTES.md).

## Feature status (2026-08-19)

All phases through 5.5 are implemented and device-verified on the Galaxy Tab
S9 Ultra, with the compact layouts additionally verified on a Galaxy S24
Ultra:

- Server-library and local (SAF/MediaExtractor uplink) relay playback with
  hardware HEVC decode, original audio/subtitles, epoch seeks, and direct
  local fallback at the current position.
- Negotiated server-library muxed audio/subtitles and verified cached subtitle
  fonts. Confirmed muxed sessions make no `/media` attach; old/unsupported
  confirmations retain that compatibility path unchanged.
- mDNS discovery of servers, automatic reconnect/resume with a failure
  taxonomy, mid-play model/quality/framing changes, and sustain warnings.
- MediaSession with lock-screen/notification controls, audio focus,
  background playback (foreground service with wake/Wi-Fi locks),
  Picture-in-Picture, typed mpv preferences (display-resample,
  interpolation, scaler), keyboard shortcuts, and watch-position resume for
  both sources.
- Remaining: S Pen/DeX hands-on smoke, a PGS/VobSub bitmap-subtitle sample,
  pairing/authentication (blocked on server support), and Phase 6 (SMB,
  endurance, release hardening).

## Verification workflow (server source)

1. Connect to the server control host and browse its advertised library.
2. Choose a bandwidth-labeled HEVC option or True Lossless HEVC, fit or
   cover, optionally a server-side downscale filter and GPU deband, and
   select a file. The player enters sensor landscape before the session is
   opened; connection and library screens follow the device.
3. Confirm the diagnostics overlay reports `hwdec=mediacodec` (or the
   device's MediaCodec path), `codec=hevc`, the negotiated coded size, a
   stable buffer, and zero unexplained drops.
4. Exercise pause, relative and seek-bar seeks, audio/subtitle cycling, and
   delay controls. Seek reloads intentionally use a fresh localhost Matroska
   input without an mpv `start=` option.
5. Confirm `relay auxiliary confirmed=muxed/embedded` or `muxed/cached` in the
   redacted log. For cached mode, repeated seeks must report no new misses;
   a later open should report cache hits.

## Verification workflow (local source)

1. Connect to the relay server, open **Local**, and choose a video or a
   folder through Android's document picker. The app browses selected trees
   in-place; read access persists for file and folder recents.
2. Confirm relay playback uses the selected HEVC quality and reports the
   local source in telemetry.
3. Exercise rapid seeks and verify audio/subtitles remain aligned. Each
   epoch gets a fresh MediaExtractor descriptor while retaining one uplink
   socket.
4. Interrupt the relay session and choose **Play original**. Direct local
   playback resumes near the current position without another picker.

## Device regression tests

The instrumentation tests drive the co-installed debug app (build with
`-PcoinstallDebug=true`); its data is disposable and the release app is never
touched. Install both APKs and run them with `am instrument` rather than
`connectedDebugAndroidTest`, which uninstalls the app afterwards:

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell "am instrument -w -e auditLocalFile audit-clip-150s.mp4 -e auditMediaPath '<library file>' org.upscalerelay.android.debug.test/androidx.test.runner.AndroidJUnitRunner"
```

- `AuditFixesDeviceTest` and `AuditUiDeviceTest` (Compose) hold the
  regressions for the October 2026 audit fixes. Most run against
  `FakeRelay`, an in-process endpoint on 127.0.0.1: it answers hello,
  rejects open_session, acknowledges teardown, and serves a sortable,
  pageable, optionally delayed `/library`. They need no relay server.
- `openWhileTheLaunchConnectWaitsForTheNetworkKeepsThePlayer` uses the real
  relay (`-e auditHost`/`-e auditPort`, default 192.168.0.115:8590). It
  switches the tablet's Wi-Fi off for a few seconds so the launch connect
  waits for the network, and always switches it back on.
- The local-file tests need a 150 s clip in the debug app's files directory;
  without it they are skipped:

  ```text
  python tools/make_test_clip.py audit-clip-150s.mp4 150
  adb push audit-clip-150s.mp4 /data/local/tmp/
  adb shell run-as org.upscalerelay.android.debug cp /data/local/tmp/audit-clip-150s.mp4 files/
  ```

- The pixel-aspect test needs three 720x576, 16:15 clips: MP4, Matroska with
  display sizes, and Matroska that leaves the aspect to the bitstream.
  `tools/make_anamorphic_clips.py <dir>` writes all three; push them the
  same way.
- `ServerE2EDeviceTest` needs a real relay with the October 2026 server
  fixes: `-e e2eHost <host> -e e2ePort <port>`. It checks server_id-scoped
  history and that the anamorphic clips come back 4:3. Add
  `-e e2eBitstreamAspect true` for the bitstream-only Matroska clip. Add
  `-e e2eColour true`, with `tools/make_colour_clip.py`'s untagged 720x576
  clip pushed, to compare relayed against direct colours.
- `PlayerSeekDeviceTest` needs the real relay and `-e auditMediaPath` with a
  file of 20 minutes or more. It presses the skip buttons back to back and
  checks where playback lands.
- `Phase4PtsDeviceTest` is a manual diagnostic. It needs `-e phase4Uri` and
  fails without it.

## Telemetry and logging

- A machine-readable snapshot is written every second to the app-private
  `files/phase4-latest.json` (playback/session state, decoder, drops, A/V
  error, buffers, transport rates, selected settings, resume counters).
  Retrieve it from a debug build with:

  ```text
  adb exec-out run-as org.upscalerelay.android cat files/phase4-latest.json
  ```

  Note: the writer pauses while the automatic-reconnect loop owns playback,
  so check `generated_at` before trusting a sample.
- The opt-in user-facing log (Settings → Player → "Save diagnostic log to
  Documents") writes `Documents/UpscaleRelay/upscale-relay-<start>.log`:
  connection/session/seek/reconnect events, mpv warnings and errors
  (URL-redacted), a telemetry line every ten seconds, and uncaught-crash
  stack traces. One file per session; the newest ten are kept.

## Buffering and failure behavior

- Media framing is read on a dedicated blocking thread with a 4 MiB socket
  receive buffer; no packet crosses the Compose/coroutine UI path.
- The pre-mpv queue is capped at 256 MiB by bytes, not packet count. mpv's
  forward demuxer cache is capped at 128 MiB.
- The localhost socket send buffer is kept small so its hidden kernel queue
  cannot grow far beyond the measured application queues.
- `buffer_report` is sent every 500 ms even when no packets arrive. It
  includes mpv cache duration plus the bounded queue converted using the
  observed video bitrate.
- Bad handshakes, future epochs, oversized payloads, truncated framing,
  initial-media timeout, loopback accept timeout, and lost control/media
  sockets are classified (network-lost, connect-timeout, server-closed,
  media-stalled, server-rejected, unsupported); transient kinds are
  reconnected on demand (see `CLAUDE.md`), terminal ones surface the
  failure card.
- Android may prefer IPv6 for its generic loopback address, so the per-load
  listener binds explicitly to `127.0.0.1`, matching the URL supplied to
  mpv.
- The complete `GET /library` response lifetime and existing-controller
  socket teardown run on `Dispatchers.IO`; neither may consume or close a
  network stream on Android's main thread.
- One server downlink connection survives all seeks. Before each seek the
  receiver atomically switches to a new epoch/byte queue, closes the old
  queue, and drops stale packets. mpv gets a new IPv4 loopback listener per
  epoch.
- mpv stops before the old listener is closed, waits 150 ms, then loads the
  new stream with `rebase-start-time=no`, leaving audio as the synchronization
  clock. Every relay epoch loads with `pause=yes`. A confirmed muxed epoch
  re-enumerates/remaps explicit track choices on the first
  `PLAYBACK_RESTART` and releases the hold immediately; it never issues
  `audio-add`. A confirmed external source without auxiliary tracks also needs
  no attachment. Confirmed subtitle-only sources use one delayed `sub-add`
  without an audio wait. Other local or compatibility-external epochs issue
  one post-restart `audio-add`, wait for valid `audio-pts`, then restore the
  caller's pause intent. See CLAUDE.md.
- Stop awaits the player's ordered native command queue before retiring media
  owners. Server teardown waits for `closed`; missing acknowledgement blocks
  automatic replacement and surfaces an explicit cleanup failure.
- Initial playback and seeks use a 60-second inactivity deadline. Strictly
  advancing subtitle-index coverage for the current epoch extends it; stale
  or repeated progress ticks do not. `seek_ready` is only a flush acknowledgement.
- Confirmed cached attachments are downloaded before the first `loadfile` to
  `cache/relay-attachments/objects/<sha256>`, verified by exact size and
  SHA-256, and exposed through a temporary session font-name view. Objects
  are bounded to 512 MiB and reused across sessions; teardown removes only
  the session view. Inspect a debug build with:

  ```text
  adb shell run-as org.upscalerelay.android ls -la cache/relay-attachments/objects
  adb shell run-as org.upscalerelay.android ls -la cache/relay-attachments/sessions
  ```
- Relay loads disable mpv's network read timeout for the private loopback
  stream because an intentional user pause can leave it silent indefinitely.
  Control/downlink liveness and the playback watchdog still detect real relay
  failures; direct-local HTTP playback retains the ordinary timeout.
- Cover is center-cropped on the server before the final resize and encode,
  so the client never decodes off-screen columns. Android keeps mpv's normal
  frame-dropping policy so missed presentation deadlines stay observable.
- The landscape control overlay applies navigation-bar insets (on the Tab S9
  Ultra its lowest control ends above Samsung's taskbar region), and both
  player sheets are scrollable and inset-aware for multi-track files.
- The Activity-scoped ViewModel retains the player and network session, so
  ordinary configuration recreation replaces only the `SurfaceView`; a stale
  callback from an older view cannot detach the newer Surface. Back from
  playback tears down sockets and the server session before reconnecting.
- Background playback holds a partial WakeLock and WifiLock from the
  foreground service — without them, standby Wi-Fi power-save kills the
  relay sockets within seconds of screen-off. Video parks in mpv's null vo
  while backgrounded and catches back up to the audio clock on return.
