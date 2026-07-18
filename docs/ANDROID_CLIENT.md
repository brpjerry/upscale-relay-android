# Android client plan

Status: **Phase 5.5 implemented and device-verified except its phone/
small-screen layouts (deferred until a phone is attached) and the S Pen/DeX
interactive smoke: media notification and lock-screen controls, screen-off
background playback, Picture-in-Picture, typed mpv preferences, keyboard
input, and the UI consistency pass all passed on the target tablet, on top
of Phase 5's discovery/recovery gates (see
[ANDROID_DEVICE_NOTES.md](ANDROID_DEVICE_NOTES.md#phase-55-device-results--2026-07-17)).
Pairing/authorization stays deferred until the server implements it. Phase 2
bitmap-subtitle validation remains open because the current library has no
PGS or VobSub sample**.
Architecture Option B is implemented as a new Kotlin/Jetpack
Compose application with a small internal libmpv module adapted from
mpv-android's `MPVLib`, JNI layer, and native build. It does not fork
mpv-android's complete application or inherit its UI.

The primary validation target is a Galaxy Tab S9 Ultra running Android 16.
The Android client must eventually reach desktop feature parity except for
FFV1 playback, but the first phase intentionally proves only the riskiest
foundations: protocol compatibility, sustained lossless-HEVC transport, native
hardware decode, and crash-free Android lifecycle handling.

## Product and platform decisions

- Kotlin, coroutines, StateFlow, kotlinx.serialization, and Jetpack Compose.
- Material 3 Adaptive for the eventual tablet UI.
- `compileSdk` / `targetSdk` 36 for Android 16; initial `minSdk` 29.
- `arm64-v8a` only during the hardware proof. Other ABIs are a release-phase
  decision.
- A pinned mpv-android/libmpv toolchain, initially following upstream's NDK
  r29 build. The upstream commit and every native dependency version must be
  recorded so builds are reproducible.
- No FFmpegKit dependency; it is retired. Phase 4 uses Android MediaExtractor
  behind a small encoded-packet interface. This avoids adding an unversioned
  JNI bridge to the pinned FFmpeg binaries while still providing access units,
  PTS, sync flags, codec initialization data, and previous-sync seeking.
- Protocol v1 and the existing Python server remain unchanged unless device
  testing proves an additive protocol field is necessary.
- Android supports the hardware-decoded HEVC choices only: true-lossless HEVC
  and the six advertised lossy bandwidth classes. `lossless-ffv1` is intentionally
  unsupported because Android has no FFV1 hardware decoder; sustained software
  decode would waste battery and increase thermal load. This does not remove
  FFV1 from the server, protocol, or desktop client.
- One active player/session per application process, matching the server's
  one-session-per-control-connection model.
- Trusted-LAN cleartext `ws://`, `http://`, and TCP are allowed initially.
  Pairing, authorization, and TLS belong to a later security phase.

## Proposed module layout

```text
android_client/
  app/                 Compose UI, navigation, lifecycle, dependency wiring
  relay-protocol/      JSON messages, framing, tokens, epochs, golden tests
  relay-client/        control, HTTP library, media sockets, session state
  player-mpv/          libmpv JNI, Surface integration, properties, commands
  relay-demux/         SAF MediaExtractor demux + private Range HTTP bridge
  storage-smb/         later: direct SMB browser and random-access source
```

Only `app`, `relay-protocol`, `relay-client`, and `player-mpv` are needed for
Phase 1. mpv APIs must stay behind a small `PlayerEngine` interface so native
details do not leak into protocol or Compose code.

## Cross-cutting rules

### Protocol fidelity

- Port the framing format literally, including signed PTS/DTS, EOS,
  discontinuity, epoch, token padding, and little-endian encoding.
- Generate shared golden byte fixtures from the Python implementation and
  consume them in Kotlin unit tests. Kotlin-generated frames must also decode
  in Python.
- Drop stale epochs before buffering or passing payload bytes onward.
- Send live buffer reports on a timer, including time spent in queues before
  mpv; never report only when packets arrive.
- Keep all queues bounded. Backpressure must stop reads or producers rather
  than converting a slow consumer into unbounded memory growth.

### Threading and lifecycle

- Use dedicated blocking workers for high-throughput media receive and the
  localhost mpv sender. Coroutines coordinate lifecycle and state but do not
  perform one suspension or dispatch per media packet.
- A single `SessionController` owns the WebSocket, media sockets, bounded
  buffers, mpv engine, and teardown job. Teardown is idempotent.
- The session owner survives Activity recreation. Compose owns only UI state
  and the current Android `Surface`; it may detach and reattach that surface
  without destroying the network session.
- Phase 1 has no user-facing background-playback feature, MediaSession, or
  notification. A transient background/foreground transition deliberately
  retains the Activity-scoped session and reattaches the Surface on return;
  this lifecycle path is covered by the device acceptance run.
- Native callbacks are translated into immutable Kotlin state before reaching
  Compose. No UI call may block on mpv or a socket.

### mpv input path

The relay packet header cannot be given directly to a media player. Match the
desktop's proven design:

```text
server framed TCP downlink
  -> dedicated receiver
  -> epoch check + payload extraction
  -> bounded pre-mpv queue
  -> per-load localhost TCP stream
  -> libmpv streaming Matroska input
  -> Android MediaCodec / Surface
```

Every load and seek receives a fresh localhost endpoint. Closing or replacing
an epoch must unblock both socket workers immediately.

### Observability

Even when the user-facing UI is minimal, every phase must expose a developer
overlay and structured logs with:

- session state and epoch;
- received and loopback Mbps;
- bridge and pre-mpv queue bytes/packets;
- mpv cache duration and total reported buffer;
- active decoder (`hwdec-current`), output dimensions, and frame rate;
- dropped frames, rebuffer events, and A/V drift when audio is added;
- teardown reason and last protocol/native error.

## Phase 1 — robust server-library video MVP

### Goal

Prove on a physical Galaxy Tab S9 Ultra that an Android client can speak the
existing protocol, select a server-library file, receive the upscaled Matroska
downlink, and decode `lossless-hevc` smoothly at the tablet's display
resolution without crashes, stalls, or unbounded buffering.

This is a hardware/protocol proof, not a usable media player yet.

Target-device validation on 2026-07-15/16 rendered a server-library
`lossless-hevc` stream through `c2.qti.hevc.decoder` / mpv `mediacodec` at
2960×1664 and 23.976 fps. The run covered natural EOS, more than 30 minutes of
playback, repeated session teardown, Surface recreation, lifecycle recovery,
and network/server failure injection. See
[ANDROID_DEVICE_NOTES.md](ANDROID_DEVICE_NOTES.md) for the evidence.

### Included

#### Build and native player foundation

- Create the Gradle project and four Phase-1 modules.
- Import the minimal mpv-android JNI and Surface code into `player-mpv` with
  upstream attribution, pinned sources, and a reproducible native build.
- Wrap the native singleton in a lifecycle-checked `PlayerEngine`.
- Embed its `SurfaceView` in Compose through `AndroidView`.
- Keep connection and library browsing adaptive, then request sensor landscape
  only for the video view. Wait for the landscape Surface dimensions before
  opening the server session so negotiation uses 2960×1848 rather than the
  portrait bounds. Android 16's large-screen orientation opt-out is scoped to
  this Phase-1 Activity in the manifest.
- Implement surface detach/reattach across rotation, resize, split-screen, and
  Activity recreation without destroying the active session.
- Evaluate both efficient Android decode paths on the target:
  `mediacodec`/`mediacodec_embed` and mpv's GPU output path. Phase 1 records
  the selected path and why; it does not assume desktop mpv options transfer
  unchanged.

#### Minimal application flow

- One screen with manual `host:port`, Connect, connection state, and server
  library listing.
- Fetch `capabilities` and `GET /library`.
- Display folders and playable files; visual polish and adaptive multi-pane
  behavior are deferred.
- Selecting a file automatically opens a `server_file` session with a fixed
  or developer-selected model, `lossless-hevc`, and the current render target
  size, then begins playback.
- Back or selecting another file performs a complete teardown before opening
  another session.

#### Protocol and transport

- WebSocket `hello`, `capabilities`, `open_session`, `play`, timed
  `buffer_report`, errors, state changes, teardown, and close handling.
- Server-source `session_opened` handling with no uplink attachment.
- Downlink handshake, packet parsing, epoch-zero validation, discontinuity,
  EOS, truncation, and connection-loss handling.
- Dedicated blocking receiver with large reads/batches and a bounded handoff.
- Dedicated per-load localhost TCP sender into mpv.
- Buffer accounting equivalent to desktop: mpv cache plus protocol/loopback
  queues converted to approximate media duration.

#### Robustness behavior

- Explicit state machine:

  ```text
  Disconnected -> Connecting -> Browsing -> Opening -> Buffering -> Playing
        ^                                             |             |
        +---------------- Closing <-------------------+-------------+
                                  -> Failed
  ```

- Timeouts for control connection, media attachment, initial bytes, and
  teardown.
- Idempotent close from every state.
- User-visible failure state plus structured diagnostic detail.
- No automatic reconnect yet; a failure must terminate promptly and make a
  clean manual retry possible.
- Memory remains bounded when mpv pauses reading or the UI thread is busy.

### Explicitly excluded

- Audio and subtitles, including the server `/media` attachment.
- Play/pause/stop buttons, seek bar, epoch-changing seeks, and gestures.
- Local files, SAF, local demux, uplink, and local fallback.
- Quality/model/fit controls intended for normal users. Developer constants or
  a basic debug selector are sufficient.
- Direct SMB, server discovery, pairing, TLS, automatic reconnect, and resume.
- Background playback, MediaSession, notifications, and Picture-in-Picture.
- Production styling, system light/dark theme integration, phone layouts, and
  release packaging.

### Tests

- Pure Kotlin framing and JSON tests using Python-generated golden fixtures.
- JVM tests for state transitions, stale epochs, timeouts, idempotent teardown,
  and bounded queue behavior.
- Integration test against the real Python server using a server-library
  sample, verifying session metadata and complete Matroska delivery.
- Instrumented tests for device-side malformed framing, plus manual device
  cycles for Surface replacement and repeated player creation.
- An on-device stress command/screen that records metrics without relying on
  visual observation alone.

### Acceptance gate

Phase 1 is complete only when all of these pass on the Tab S9 Ultra running
Android 16:

1. A server-library file opens with no uplink and renders at the negotiated
   tablet resolution through hardware HEVC decoding.
2. A representative 24/30 fps `lossless-hevc` stream plays for 30 minutes with
   no post-startup rebuffer, no native crash, and no visible judder. Target
   unexplained dropped-frame rate is zero; the hard gate is below 0.1%.
3. `hwdec-current`, codec name, coded size, average bitrate, buffer level, and
   frame drops are captured in a device report rather than inferred.
4. The total client buffer stabilizes around the server watermark; no queue or
   process-memory trend grows for the duration of the run.
5. Twenty open/play/teardown cycles complete without leaked sessions, sockets,
   threads, or native crashes.
6. Ten Surface recreation cycles during playback—rotation, split-screen
   resize, or Activity recreation—recover without restarting the server
   session or corrupting video.
7. Server termination, Wi-Fi interruption, truncated media frames, and app
   background/foreground transitions each produce a bounded-time failure or
   documented teardown, never a hang or crash. Automatic recovery is not
   required.
8. Kotlin/Python protocol conformance tests and the existing Python suite are
   green.

Phase 1 produces `docs/ANDROID_DEVICE_NOTES.md` containing build identifiers,
mpv/FFmpeg versions, selected decode/output path, test media properties,
throughput, buffer graphs or summaries, frame-drop results, memory behavior,
and thermals observed during the 30-minute run.

All eight items passed on the target tablet on 2026-07-15/16. Phase 1 remains
intentionally video-only; Phases 2 and 3 have since built the synchronized
player and tablet product shell on top of this proof.

## Phase 2 — playback correctness and synchronized original tracks

### Goal

Turn the video proof into a correct server-library player by exercising the
hard synchronization and seek semantics before investing in product UI.

### Scope

- Attach the server `/media` URL as mpv's external audio and subtitle source.
- Preserve absolute PTS with `rebase-start-time=no` and validate audio as the
  master clock.
- Add minimal play/pause, stop, position, seek bar, relative seek, and
  rebuffering controls.
- Implement the complete epoch seek dance, fresh Matroska/loopback input per
  epoch, stale packet rejection, and rapid-seek coalescing.
- Subtitle enumeration/selection, ASS/SSA styling, PGS/VobSub rendering, and
  subtitle delay.
- Exercise the Android HEVC bandwidth choices and true-lossless HEVC through
  hardware HEVC decode. Do not advertise FFV1 in Android quality selection.
- Implement fit/cover and fullscreen crop behavior.
- Match desktop telemetry for buffer, bitrate, decoder, A/V drift, and drops.

### Implementation status (2026-07-16)

The Phase 2 code is implemented, host-tested, and device-tested on the Galaxy
Tab S9 Ultra. The A/V endurance, seek storm, SSA subtitle, control, tier, and
fit/cover checks passed. The only unrun acceptance item is bitmap subtitle
rendering because every subtitle-bearing file in the configured test library
uses SSA; no PGS or VobSub sample was available.

- The control client now negotiates either Android HEVC tier and fit/cover,
  builds the original `/media` URL, sends pause and absolute-PTS seek messages,
  and matches `seek_ready` to the requested epoch.
- Session metadata includes exact source `time_base` and average frame rate so
  UI seconds are converted back to protocol PTS without guessing.
- A single blocking downlink socket survives seeks. Its epoch route is swapped
  atomically to a fresh byte-bounded queue and localhost listener before the
  seek is sent; older packets are discarded and a closed superseded queue
  cannot fail the new epoch.
- Rapid UI seeks cancel and join the preceding request before allocating the
  next epoch. mpv is stopped first, the old input is allowed 150 ms to tear
  down, and the replacement is loaded without `start=`.
- mpv uses `rebase-start-time=no`, audio-clock synchronization, and per-load
  `audio-file` plus `sub-files-append` options pointing at the original server
  file. Cover is center-cropped server-side before resize/encode, so the
  Android decoder does not process off-screen overflow. Android retains mpv's
  normal frame-drop policy so missed presentation deadlines stay observable.
  Commas and equals signs in URLs use mpv's fixed-length option syntax.
- The landscape diagnostic player provides stop, pause/play, a position slider,
  +/-10 second seeks, audio/subtitle track cycling, audio/subtitle delay,
  tier and fit/cover selection, and live seek progress.
- Telemetry now includes selected hardware/video/audio decoder information,
  position, duration, A/V drift, delays, rebuffer state, bitrate, queue/cache
  depth, and decoder/output drops. The current build writes the unified snapshot
  to `files/phase4-latest.json` for both server-library and local playback.
- JVM coverage includes metadata parsing, epoch route replacement/stale packet
  rejection, state transitions, bounded queues/framing, and mpv load-option
  escaping. SSA rendering, track selection, and delay changes passed with the
  packaged libmpv build; bitmap subtitle rendering still needs a device sample.

Target-device results are recorded in
[ANDROID_DEVICE_NOTES.md](ANDROID_DEVICE_NOTES.md#phase-2-device-results--2026-07-16).
The lossless tier completed more than 30 aggregate minutes and natural EOS with
audio attached, zero decoder drops, no cache rebuffer, and measured A/V error
near zero. A 25-action seek storm converged on epoch 25 in the original server
session with no stale frames or deadlock. A normal relative seek took about 5
seconds and a far seek about 13 seconds; the stress storm's final reload took
about 28 seconds, so reload latency remains a performance-polish item rather
than a correctness failure.

### Acceptance gate

- Thirty-minute A/V run with no perceptible drift and measured drift inside a
  defined tolerance (initial target: ±50 ms).
- Twenty-five-action seek storm with correct final target, no stale frames,
  no deadlock, and audio/subtitles aligned after reload.
- ASS and PGS samples render correctly; subtitle track and delay changes apply
  during playback.
- Every advertised Android HEVC quality choice negotiates and plays through hardware decode, with
  unsupported/too-slow device behavior surfaced rather than silently failing.

Device status on 2026-07-16: the A/V run, seek storm, SSA track/delay controls,
and the original two HEVC choices passed. The newly exposed bandwidth ladder
still needs a full device matrix pass. PGS/VobSub is pending solely for lack of a sample in
the configured library. Phase 3 subsequently built the product shell without
changing the proven protocol/player core.

## Phase 3 — tablet-first Android product shell

### Goal

Replace the diagnostic UI with an adaptive Android player optimized for large
screens while retaining the proven playback core.

### Scope

- Material 3 large-screen navigation and list/detail library layouts that
  respect Android's current system light/dark mode.
- Navigation rail for Server, Local, Recent, and Settings on tablet-sized
  windows. Phase 4 has since replaced the original Local placeholder with the
  Android document-picker workflow.
- Full-bleed player with auto-hiding touch controls, large seek targets,
  subtitle sheet, and model/quality/fit settings sheet.
- Landscape, portrait, split-screen, edge-to-edge, predictive back, and
  tablet touch interactions.
- Gesture seeking, volume, and brightness with an option to disable gestures.
- Persist server, autoconnect, model, tier, fit mode, subtitle, and diagnostic
  preferences with DataStore.

### Implementation status (2026-07-16)

Phase 3 is implemented and device-tested on the Galaxy Tab S9 Ultra. The
diagnostic launch screen has been replaced by the tablet product shell while
the Phase 2 protocol, epoch, and libmpv paths remain unchanged.

- A Material 3 navigation rail exposes Server, Local, Recent, and Settings.
  Server browsing uses a tablet list/detail layout; Local now opens the Phase 4
  Android document-picker and persisted-recent workflow.
- DataStore persists host/port, autoconnect, selected model, Android HEVC choice,
  fit/cover, server-side downscale filter, GPU deband, subtitle default, diagnostics,
  gestures, and a bounded ordered
  recent-file list. Recent playback resolves the persisted path against the
  current server library rather than bypassing its sandboxed identity.
- The full-screen player enters sensor landscape and immersive mode only while
  video is active. Its top and bottom chrome auto-hide after four seconds and
  expose play/pause, ±10 seconds, an absolute slider, audio/subtitle track and
  delay controls, and model/tier/framing defaults.
- Horizontal drags preview and commit protocol seeks; left- and right-side
  vertical drags adjust window brightness and media volume. Gestures can be
  disabled without removing tap-to-show controls.
- The shell follows Android's system light/dark mode. There is no separate app
  theme, TalkBack milestone, phone layout, keyboard/S Pen/DeX handling,
  background playback, or lock-screen integration in this phase; those are
  intentionally assigned to Phase 5.5.
- Both player sheets are vertically scrollable and navigation-inset aware, so
  multi-track files keep their lower delay controls reachable above Samsung's
  landscape taskbar.
- Structured telemetry is written to `files/phase4-latest.json` in the current
  build and adds the
  selected model, tier, fit mode, and gesture state to the Phase 2 playback
  metrics.

On-device playback negotiated 2960×1664 lossless HEVC at 23.976 fps through
`mediacodec`. The initial sample reached roughly 205 Mbps with about 7.5
seconds cached and zero decoder/output drops. A later horizontal gesture seek
completed through the epoch protocol and recovered to roughly 9 seconds of
buffer with zero drops and measured A/V error near zero. Pause/resume, both
bottom sheets, track controls, recents after process replacement, system
light/dark recreation, auto-hiding chrome, and all three gesture directions
were exercised on the physical tablet. See
[ANDROID_DEVICE_NOTES.md](ANDROID_DEVICE_NOTES.md#phase-3-device-results--2026-07-16).

An additional exact-file Cover regression reproduced 28–30 mpv output drops,
visible catch-up, zero decoder drops, and a nonempty cache. `framedrop=no`
suppressed the counter without fixing the visible behavior and was removed.
The implemented server now center-crops Cover before the final configurable
downscale, changing the Tab S9 Ultra stream from 3286×1848 to 2960×1848 and
removing off-screen columns from the lossless bitstream. The exact-file device
regression remains to be rerun against this implementation.

### Acceptance gate

- All Phase-2 functions are reachable without a desktop-style toolbar.
- State survives rotation and tablet window-size changes; player controls do not
  obscure video permanently or become unreachable.
- The library and player follow Android system light/dark mode without a
  separate in-app theme requirement.
- Touch interaction passes on representative tablet window sizes.

The target-tablet gate passed for the full portrait shell and immersive
landscape player at the S9 Ultra's native dimensions. Additional resize and
non-Samsung tablet coverage remains part of the release matrix rather than an
unimplemented Phase 3 feature.

## Phase 4 — local files, demux, uplink, and fallback

### Goal

Add the desktop client's local-source path without weakening the proven
server-library player.

### Scope

- SAF file and directory selection with persisted URI grants and recent roots.
- A small Android MediaExtractor adapter that opens a fresh seekable descriptor
  per generation, reports metadata/codec initialization data, and emits
  original encoded access units with exact PTS and keyframe flags. Protocol
  DTS is absent because MediaExtractor does not expose it; packet order remains
  decode order and the wire format explicitly permits an absent DTS.
- Framed uplink with batching, cancellation, bounded queues, EOS, and seek from
  the nearest prior keyframe.
- Attach the same local source to mpv for original audio and subtitles. Handle
  repeated opens using duplicated descriptors or a documented content bridge;
  do not assume a one-shot stream is sufficient.
- Local/server source parity for controls, seeks, subtitles, all Android HEVC
  choices, fit/cover, telemetry, and errors.
- Direct local fallback at the current position if the relay session fails.
- Cross-language PTS equivalence and seek-storm tests using the same fixtures
  as desktop.

### Acceptance gate

- Full-file local-source playthrough preserves the Python client's PTS
  sequence.
- Local audio/subtitles stay aligned before and after rapid seeks.
- Fallback switches to direct local playback at the current position without
  reopening the Android document picker.
- Cancellation never leaves a native demux operation using a closed descriptor
  or stealing packets from a newer seek generation.

### Implementation status (2026-07-17)

Phase 4 is implemented and device-accepted. The physical-device pass covered
media compatibility with two long-form H.264 MKVs, exact PTS equivalence
against the Python demuxer (34k packets per file; one file's MediaExtractor
sync flags are a strict subset of libav's container keyframes, which is
informational because the server never reads the uplink keyframe flag), a
27-action seek storm over one server session with microsecond A/V error and a
clean logcat, natural EOS through the epoch-scoped uplink EOS, and direct
local fallback resuming at the audio-clock position without a picker. One
defect was found and fixed: `hwdec-codecs` allowed only `hevc`, forcing
software decode when fallback played an H.264 original; it now allows
`h264,hevc,vp8,vp9,av1`. Details in
[ANDROID_DEVICE_NOTES.md](ANDROID_DEVICE_NOTES.md#phase-4-device-results--2026-07-1617).

- Local uses Android's `OpenDocument` and `OpenDocumentTree` contracts,
  requests persistent read access, and keeps bounded recent-file and
  recent-root lists in DataStore. The Local destination browses a selected
  tree in-app and can reopen either kind of grant without showing the picker
  again.
- `relay-demux` selects the encoded video track with MediaExtractor, publishes
  microsecond PTS (`time_base=1/1000000`), codec initialization data, display
  dimensions, duration, frame rate, and keyframe flags, and converts common
  length-prefixed AVC/HEVC access units to Annex B for the server decoder.
- Every initial play or seek owns a new extractor and descriptor. The
  persistent uplink socket cancels and joins the old reader before installing
  a newer epoch, batches sixteen protocol packets per write, marks the first
  post-seek packet discontinuous, and sends epoch-scoped EOS.
- A private Range-capable `127.0.0.1` HTTP bridge opens an independent SAF
  descriptor for each mpv request. The same document can therefore be read
  concurrently for external audio, subtitles, and seek probes without sharing
  a mutable file position with the uplink demuxer.
- Local relay playback uses the same player, controls, quality/framing/filter
  settings, telemetry, and epoch reload path as server-library playback.
  Following a relay failure, **Play original** switches mpv to the local HTTP
  bridge at the current position without reopening the picker.
- JVM tests cover NAL normalization, HTTP byte ranges, and persistent-uplink
  epoch/discontinuity/EOS framing. The debug APK and all Android modules build
  without an NDK or additional native binary.

## Phase 5 — discovery, recovery, and desktop parity

Direct SMB browsing and shared-mount identity mapping were originally scoped
here; both are deferred to Phase 6 so recovery and discovery land first.

### Scope

- Android NSD discovery for `_upscalerelay._tcp` while preserving manual host
  entry.
- Pairing, persistent client credentials, and server/API authorization when
  the server implements them.
- Automatic reconnect/session resume, network-change handling, keepalives,
  timeout taxonomy, and actionable recovery UI.
- Capability-aware model/tier presentation, mid-play model changes, and
  warnings when the server or device cannot sustain the selected combination.
- Feature-parity audit against the current desktop client, with FFV1 recorded
  as the intentional Android exception.

### Acceptance gate

- Local and server-library sources expose the same player behavior.
- Ten-second network interruption resumes at the correct epoch/position or
  fails with a clear recovery action.
- Credentials and media URLs are absent from ordinary logs and persisted only
  in approved secure storage.

### Implementation status (2026-07-17)

Phase 5 is implemented and its discovery, interruption-resume, mid-play
settings, and sustain-warning behaviors were exercised on the target tablet
the same day.

- **Discovery.** The server now advertises `_upscalerelay._tcp` over
  mDNS/DNS-SD (`relay_server/mdns.py`, `zeroconf`, disabled with `--no-mdns`
  or when the package/address is unavailable; test servers stay silent). The
  Android connect panel and Settings browse the service with `NsdManager`,
  list resolved servers under "Discovered on this network", and one tap
  fills host/port and connects. Manual entry is unchanged. The Tab S9 Ultra
  resolved the Windows server as `upscale-relay on <hostname>` and connected
  from the discovered entry.
- **Automatic reconnect/resume.** Controller failures are classified into a
  timeout taxonomy (`FailureKind`: network-lost, connect-timeout,
  server-closed, media-stalled, server-rejected, unsupported) in
  `relay-client`, with only transient kinds eligible for recovery.
  A recoverable failure during relay playback pauses the player immediately
  (freezing the audio clock that previously kept running from the local
  bridge), captures the position, and reopens — new control connection, new
  session for the same server path or SAF document, epoch seek to the
  captured position — under a `ReconnectPolicy` (six attempts, 1→8 s
  exponential backoff, 60 s budget). A `ConnectivityManager` callback
  short-circuits the backoff the moment a network returns, and a stall
  watchdog treats fifteen seconds of a starved player with no downlink bytes
  as a failure. The recovery overlay names the failure kind, shows the
  attempt count, and offers **Stop trying** plus **Play original** for local
  sources; exhaustion surfaces the taxonomy label with the usual recovery
  card. On the device, a ten-second Wi-Fi cut at 62.8 s resumed playback
  automatically ~15 s after Wi-Fi returned, position-continuous, with
  `auto_resume_count` incremented. The behavior is preference-gated
  ("Reconnect automatically during playback", default on).
- **Mid-play settings.** Model, quality, framing, and downscale-filter
  changes from the active player's sheet now restart the session at the
  current position through the same reopen path and confirm the new value in
  session metadata; the sheet says so. On-device: a model change resumed
  within ~5 s at the same position, a tier change within ~4 s. When a change
  outruns a slow server (a first-use TensorRT engine build), the failure
  rolls into the reconnect loop and recovers on its own.
- **Sustain warnings.** Repeated steady-state rebuffers raise a dismissable
  in-player banner naming the model/tier ("The server is not keeping up
  with…"), verified live with `realesrgan-x2-ts`; sustained decoder drops
  raise the device-side equivalent. Warm-up buffering after session start or
  restart is excluded.
- **Log hygiene.** Forwarded mpv log lines have URLs redacted (media URLs
  embed file paths), and the client logs failure kinds/exception types
  rather than message bodies. No credentials exist yet to store.
- **Pairing/authorization.** Deferred, as scoped: the server implements no
  pairing or authentication; protocol v1 remains cleartext on a trusted LAN.

### Desktop parity audit (2026-07-17)

| Capability | Desktop | Android |
|---|---|---|
| Server-library browse and relay playback | yes | yes |
| Local-file relay (uplink demux) | yes | yes |
| Direct local fallback at position | yes ("Play locally") | yes ("Play original") |
| Pause, epoch seeks, track/delay controls, fit/cover, filters | yes | yes |
| FFV1 lossless tier | yes | intentionally absent (no hardware decoder) |
| HEVC lossless + bandwidth ladder | yes | yes |
| mDNS discovery of servers | not yet (server advertises) | yes |
| Automatic reconnect/resume | no (manual retry) | yes |
| Mid-play model/quality changes | no | yes |
| Sustain warnings | no | yes |
| Watch-position resume | no | yes |
| mpv.conf preference passthrough | yes | Phase 5.5 (typed settings) |
| SMB browsing | OS mounts only | Phase 6 |

Android now leads the desktop client on discovery, recovery, and mid-play
changes; backporting those is desktop-roadmap work, not an Android gap. The
remaining Android gaps are intentional (FFV1) or scheduled (mpv preferences
in Phase 5.5, SMB in Phase 6).

## Phase 5.5 — additional form factors, media integration, and UI polish

### Goal

Add phone/small-screen support, Android background-media integrations, and a
deliberate product-wide UI consistency pass after the tablet UI and all
source/recovery paths are established.

### Scope

- Compact navigation and player layouts for phone-sized and other small
  windows, without changing the tablet-first Phase 3 information architecture.
- MediaSession integration, media notification, audio focus, lock-screen
  metadata and controls, and an explicit background-playback policy.
- Picture-in-Picture behavior coordinated with the MediaSession and background
  playback lifecycle.
- Keyboard and mouse navigation, S Pen interactions, and Samsung DeX/freeform
  window behavior.
- Persisted support for common `mpv.conf` playback preferences through typed
  Android settings, including display-resample video sync, interpolation, and
  interpolation scaling. Relay-owned plumbing such as the input URL,
  `rebase-start-time`, hardware decode, and per-epoch reload behavior remains
  protected from incompatible overrides.
- Persist and restore the appropriate playback state across foreground,
  background, lock-screen, and Picture-in-Picture transitions.
- Audit every destination, dialog, sheet, player overlay, loading state, and
  error transition for consistent behavior. State restoration must not briefly
  flash an obsolete destination, empty library, connect panel, or player before
  the persisted/current state is available.
- Remove controls that have no effect in their current context. Where the
  action is useful, complete it instead: for example, changing model, quality,
  framing, or downscale settings from the active-player sheet must restart the
  relay session at the current position and visibly apply the new selection,
  rather than changing only the next-video preference.
- Replace text glyphs and word-only transport/navigation actions with a
  coherent Material icon set where an established icon exists. Preserve text
  labels or tooltips/content descriptions where the icon alone would be
  ambiguous.
- Normalize navigation, Back behavior, busy/disabled states, transient error
  handling, selection feedback, sheet dismissal, and control auto-hide timing
  across local, server, recent, settings, fallback, and player flows.
- Audit visual hierarchy, spacing, typography, truncation, touch targets, and
  empty/loading/error states at the supported tablet and Phase 5.5 window
  sizes. Remove duplicate status text and implementation/debug terminology
  from the normal product UI.

### Implementation status (2026-07-17)

Phase 5.5 is implemented and device-verified on the Tab S9 Ultra, and its
phone/small-screen layouts are implemented and verified on a Galaxy S24
Ultra (SM-S928U1, 411 dp width). Only the S Pen/DeX interactive smoke tests
still need hands on the device.

- **Compact layouts (phone-verified).** Windows narrower than 600 dp swap
  the navigation rail for a bottom navigation bar and the two-pane server
  browser for a single-pane list whose detail opens full-screen behind an
  "← All videos" / system-Back return; content padding tightens and the
  connect card goes full-width. The landscape phone player shows the full
  chrome — transport icons, slider, sheets — without clipping, and the
  session negotiated the phone's real 1920×1080 surface. Discovery, the SAF
  grant flow, local HEVC relay playback, keyboard seeks, and clean teardown
  were all exercised on the phone.
- **Diagnostic file logging (added at owner request).** Settings → Player →
  "Save diagnostic log to Documents" writes an opt-in per-session log to
  `Documents/UpscaleRelay/` through MediaStore: session/connect/seek/
  reconnect events, URL-redacted mpv warnings and errors, a playback
  telemetry line every ten seconds, and uncaught-crash stack traces via a
  chained crash handler. A non-blocking bounded queue feeds a flushing
  writer thread; the newest ten files are retained, and the log restarts
  automatically on each launch while enabled. Verified on the tablet,
  including automatic restart across a force-stop.
- **Playback progress resume (added at owner request).** The client
  persists per-file watch positions (bounded, most-recent-first map in
  DataStore keyed by server path or SAF document) every five seconds during
  playback, clears them when a playthrough finishes or ends within the last
  ninety seconds (credits), and on reopening a file seeks the new session
  to the stored position through the epoch protocol before mpv attaches —
  for both server-library and local sources. Verified on the phone in both
  modes: reopening resumed at the saved position instead of zero.

- **System media integration.** A platform `MediaSession` (owned by the
  playback ViewModel) mirrors title/duration metadata and live position, and
  a foreground `PlaybackService` renders it as a MediaStyle notification for
  the shade and lock screen. Media keys and session callbacks drive
  play/pause/seek/stop through the same paths as the on-screen controls —
  verified end to end (pause froze both mpv and the relay session; resume
  continued exactly where it stopped). Audio focus is requested with
  media/movie attributes; transient loss pauses and regains resume.
- **Background playback policy (explicit).** With "Continue playback in the
  background" on (default), leaving the app or turning the screen off keeps
  the relay session and audio running: the service holds a partial WakeLock
  and a WifiLock (without them, Samsung's standby Wi-Fi power-save killed the
  relay sockets within ~30 s of screen-off), and the detached Surface parks
  video in mpv's null vo. Verified: 30 s of screen-off with live telemetry,
  1× position advance, and the server session still playing. On return the
  video output re-attaches and the video track catches back up to the audio
  clock (measured ~20 s of fast-forward catch-up after a 30 s park, then
  A/V error back to microseconds with zero decoder drops). With the setting
  off, backgrounding pauses playback instead.
- **Picture-in-Picture.** The player auto-enters PiP on Home while video is
  active (aspect ratio from the negotiated downlink size, play/pause
  RemoteAction, chrome-free layout), keeps playing at exactly 1×, and
  expands back to the full player cleanly. Closing the PiP window ends
  playback and the session via normal teardown.
- **Typed mpv preferences.** Display-resample video sync, motion
  interpolation, and the interpolation scaler (oversample/linear/
  catmull_rom/mitchell) persist in DataStore, apply live, and are exposed in
  Settings and the player sheet. Relay-owned plumbing (vo,
  rebase-start-time, hwdec, epoch reloads) is not user-configurable.
  Verified with interpolation on: hardware HEVC decode retained, epoch seek
  clean, external-audio sync at ~30 µs, zero output drops over a 20 s
  steady-state window.
- **Keyboard.** Space/K toggles pause, arrow keys and J/L seek ±10 s through
  the epoch protocol, media keys route via the MediaSession — all verified
  over adb key events. S Pen behaves as a pointer (no special handling
  required); DeX/freeform and S Pen hover remain manual smoke items.
- **UI consistency.** The navigation rail, library/local lists, and player
  transport use Material icons with content descriptions (back arrow,
  Replay10/PlayArrow/Pause/Forward10, subtitle and tune icons, folder/movie
  glyphs). The last destination persists and restores without flashing an
  obsolete screen (the shell renders nothing until preferences load). Hidden
  dot-directories are filtered from the Local browser, opaque downloads
  document ids display as "Downloads video", and the server header shows
  Connected/Connecting/Disconnected instead of state-machine names.
- **Defect found by the 5.5 pass, explained retroactively:** the Phase 4
  "Back from ENDED exits the app" observation was actually a LazyColumn
  duplicate-key crash — the same document URI appeared in both the Movies
  tree listing and the recent-files list of the Local destination. Keys are
  now namespaced per section, and Back from the ENDED state returns to the
  library as intended. A second race was found and fixed on-device: the
  playback service stopped itself at startup if its first bridge snapshot
  preceded the first metrics tick (the notification flashed and vanished);
  the bridge is now marked active before the service starts.

### Acceptance gate

- Every Phase-2 player function remains reachable on representative
  phone-sized windows without clipping or obscured controls.
- Lock-screen and notification play/pause/seek actions stay synchronized with
  the active relay session and show the correct media metadata.
- Background and Picture-in-Picture transitions follow the documented policy
  without leaking a session, losing A/V sync, or unexpectedly continuing
  playback.
- Keyboard, S Pen, and DeX smoke tests pass without clipping, broken focus, or
  tablet-layout regressions.
- Supported mpv preferences persist and take effect on the next applicable
  load. Display-resample interpolation remains smooth without breaking
  external-audio synchronization, epoch seeks, or hardware HEVC decode.
- No destination or player flashes through an unrelated intermediate screen
  during launch, reconnect, rotation, document selection, playback start,
  fallback, seek reload, or return to the library.
- Every visible action is either functional in that context or absent. Active-
  player model/quality/framing/filter changes reopen at the same logical
  position and the resulting session metadata confirms the new value.
- Navigation and transport actions use consistent Material icons, labels,
  enabled states, and Back behavior; icon-only actions have meaningful Android
  content descriptions.
- Phase 5.5 changes do not regress the Phase 3 tablet layouts.

## Phase 6 — SMB, endurance, security, and release

### Scope

- Direct SMB browsing and random-access playback suitable for libavformat and
  mpv, with credentials stored through Android Keystore-backed storage
  (moved from Phase 5). SMB sources must expose the same player behavior as
  local and server-library sources.
- Shared-mount identity mapping when the server and Android device can address
  the same file under different roots (moved from Phase 5).
- Two-hour playback runs across the Android HEVC quality choices: drift, memory, frame
  drops, battery, thermals, Wi-Fi throughput, and background/foreground
  transitions.
- Device capability probe using MediaCodec profile/level, size/rate, and
  decoder properties, followed by empirical tier gating. Published codec
  ranges do not replace real lossless-stream tests.
- Test matrix centered on Tab S9 Ultra/Android 16, then additional Samsung
  tablets, at least one non-Samsung tablet, the Phase 5.5 phone layouts, and
  supported API levels.
- Reproducible native CI builds, symbol archives, dependency update policy,
  license/SBOM review, signed APK/App Bundle, and release notes.
- TLS/pairing rollout, Android network-security configuration, secret
  redaction, and malformed-input fuzzing for framing and JNI boundaries.
- Crash capture for Kotlin and symbolicated native failures, with explicit
  user consent for any telemetry leaving the device.

## Feature delivery map

| Capability | Phase |
|---|---:|
| Manual server connection and capabilities | 1 |
| Server library browsing and video playback | 1 |
| Lossless-HEVC hardware validation | 1 |
| Bounded buffering, telemetry, lifecycle-safe teardown | 1 |
| Audio, subtitles, controls, seeks, HEVC quality selection | 2 |
| Tablet UI, settings, gestures, and system light/dark mode | 3 |
| Local files, demux/uplink, and local fallback | 4 |
| Discovery, pairing, reconnect/resume | 5 |
| Direct SMB and shared-mount mapping | 6 |
| Phone/small-screen layouts | 5.5 |
| Background playback, MediaSession, lock-screen controls, and PiP | 5.5 |
| Keyboard, S Pen, and Samsung DeX support | 5.5 |
| Common mpv preferences, including display-resample interpolation | 5.5 |
| UI consistency, iconography, transition polish, and active-player settings | 5.5 |
| Endurance, security, broader devices, release packaging | 6 |

## Decisions intentionally deferred

- Whether direct MediaCodec output or mpv GPU composition is the default after
  subtitles are introduced; Phase 1/2 measurements decide.
- Whether to lower the minimum SDK below API 29, and which extra ABIs to add
  beyond the initial arm64 proof.
- Exact SMB library and credential UX.
- Store distribution versus signed private releases.
