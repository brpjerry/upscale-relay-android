# Android client — development notes

Working notes for developing and verifying the client. The README covers
architecture, building, and libraries; the phase plan and acceptance gates
live in [`docs/ANDROID_CLIENT.md`](docs/ANDROID_CLIENT.md) and the
physical-device validation record in
[`docs/ANDROID_DEVICE_NOTES.md`](docs/ANDROID_DEVICE_NOTES.md).

## Feature status (2026-07-17)

All phases through 5.5 are implemented and device-verified on the Galaxy Tab
S9 Ultra, with the compact layouts additionally verified on a Galaxy S24
Ultra:

- Server-library and local (SAF/MediaExtractor uplink) relay playback with
  hardware HEVC decode, original audio/subtitles, epoch seeks, and direct
  local fallback at the current position.
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
  media-stalled, server-rejected, unsupported); transient kinds feed the
  automatic reconnect/resume loop, terminal ones surface the failure card.
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
  new stream with `rebase-start-time=no`. The same original-media URL is
  attached through `audio-file` and `sub-files-append`, leaving audio as the
  synchronization clock.
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
