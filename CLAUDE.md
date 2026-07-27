# CLAUDE.md — upscale-relay-android

Android client for the upscale relay: browses a GPU server's library, receives
the upscaled HEVC downlink over a private TCP socket, and plays it in libmpv
with the original file's audio and subtitles pulled straight from the server
over HTTP. The server lives in the sibling repo `../upscale-relay` (Python) —
its `CLAUDE.md` covers the pipeline, and `docs/PROTOCOL.md` the wire format.

Read `README.md` (architecture, libraries), `DEVELOPMENT.md` (verification
workflows, buffering/failure behaviour), `docs/ANDROID_CLIENT.md` (phase plan,
acceptance gates, seek-latency history), and `docs/ANDROID_DEVICE_NOTES.md`
(physical-device record).

## Layout

- `app/` — Compose UI, `RelayViewModel` (session orchestration, seeks,
  telemetry, watchdogs), `PlaybackService`, DataStore prefs.
- `relay-protocol/` — framing, handshake, JSON messages, golden fixtures
  shared with the Python server.
- `relay-client/` — control WS, downlink receiver, per-epoch bounded queue and
  loopback server, session state machine, reconnect policy.
- `relay-demux/` — SAF `MediaExtractor` uplink for local files plus a private
  Range-capable `127.0.0.1` HTTP bridge.
- `player-mpv/` — `MPVLib` JNI (adapted from mpv-android) and
  `MpvPlayerEngine`, the only place mpv options and commands are set.
- `native/` — scripts that fetch the pinned libmpv binaries; no NDK needed for
  an ordinary build.

## Commands

There is no `local.properties` and `java` is not on `PATH`, so every Gradle
invocation needs these first:

```sh
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :player-mpv:test :relay-client:test \
          :relay-protocol:test :relay-demux:testDebugUnitTest
```

Releases: bump `versionCode`/`versionName` in `app/build.gradle.kts`, add
`release-notes/v<x.y.z>.md`, merge, then push a `v*` tag — `.github/workflows/
release.yml` builds and publishes the signed APK with those notes.

## Debugging on the device

- **Reproduce on the `passthrough` model first.** The server's GPU is shared
  with whatever its owner is doing, so a real model can quietly fall under
  realtime — and a starved pipeline looks exactly like the client bugs you are
  usually chasing: dropped frames, rebuffers, stalls. Passthrough removes
  inference; put the model back once the behaviour is understood. You are
  measuring contention, not the bug, when `/status →
  sessions[].pipeline.fps` sits below the source frame rate, mpv `cache`
  drains toward zero, or the app raises its own "server is not keeping up"
  banner. This cost a round of confounded frame-drop measurements on
  2026-07-26. Match the *network* too: a tier the Wi-Fi cannot carry starves
  the client just as effectively (`average_mbps` in the telemetry snapshot).
- Debug and release share an `applicationId`, so installing a debug build
  normally means uninstalling the release one and losing the user's DataStore
  (settings, watch history). Add a temporary `applicationIdSuffix = ".debug"`
  under `buildTypes { getByName("debug") { ... } }` instead, and remove it
  before committing.
- `files/phase4-latest.json` is written every second and is the fastest read
  on drops, A/V error, buffer, and transport rates:
  `adb shell run-as <applicationId> cat files/phase4-latest.json`.
- mpv is quiet by default (`msg-level=all=warn,vd=info,vo=info` in
  `MpvPlayerEngine`). Raising it to `all=v` in a debug build prints the lines
  that actually explain playback decisions — `refresh seek to <pts>` per
  external demuxer, `first video frame after restart shown`, `audio ready`,
  `playback restart complete @ <pts>`. `demux=trace` on top of that shows the
  cached-seek decisions. Revert before committing.
- mpv events reach logcat as `V mpv : event: <name>`; app state as
  `D RelayAndroid: controller state=<state>`.

## Hard rules

- **Attach the original media after playback starts, never on `loadfile`.**
  mpv positions an external demuxer at the current playback time *when the
  track is selected*, and during load that time is zero. The relay stream
  carries only the tail of the file from the seek target, so `audio-file` /
  `sub-files-append` left the audio and subtitle demuxers at the start of the
  original file and mpv reached the epoch by decoding everything before it —
  13–20 s of black screen after a far seek, scaling with the seek target.
  `MpvPlayerEngine.attachExternalMedia` adds them with `audio-add` / `sub-add`
  on the first `PLAYBACK_RESTART` instead.
- `--start=<target>` does not fix that and was tried on the device: the
  loopback stream is a live one-shot socket, so mpv rejects the seek
  (`Cached seek not possible` / `Cannot seek in this stream`). A back buffer
  and `demuxer-seekable-cache=yes` do not help either — the epoch carries one
  keyframe, so there is no cached range to seek within.
- **The epoch loads `pause=yes`.** Without it the picture runs on alone for
  the second the attach takes, and mpv reconciles that drift against a
  freshly started audio track: an A/V desynchronisation warning and tens of
  dropped frames on *every* stream start. `attachExternalMedia` lifts the hold
  and applies the caller's real pause intent when the tracks are in place.
- Adding a track mid-playback needs the `select` flag; `auto` only marks the
  file as a candidate and leaves it unselected (verified — it produced no
  audio at all). Explicit user track choices are remembered in the engine and
  re-applied after each attach so a seek cannot revert them.
- **One `audio-add`, never a matching `sub-add`.** mpv exposes *every* track
  of an external file, so the audio add already contributes this file's
  subtitle track; a second add only opens a duplicate HTTP demuxer that
  re-parses and re-seeks the same file (5+ seconds of it on a busy link) and
  makes every audio and subtitle entry appear twice in the track list. The
  subtitle has to be selected explicitly afterwards, because mpv auto-selects
  subtitles only when a file is *loaded*, not when tracks appear on one.
- **`audio-add` returning does not mean audio is ready.** mpv still has to
  seek that demuxer and decode. Releasing the pause hold when the command
  returns let the picture run for the couple of seconds that took — the exact
  drift the hold exists to prevent — and the primed-but-starved audio output
  then underran. Wait for `audio-pts` to become valid (capped, so a silent
  file cannot strand playback).
- **Measure A/V drift with `avsync`, never `position - audio-pts`.** The
  latter includes the user's audio-delay setting, so a standing 4 s delay
  reads as a permanent 4 s fault; the drift watchdog would then reload the
  epoch every cooldown, forever. mpv applies the delay and reports the
  residual error, which stays microscopic either way (verified on device:
  4.0 s delay ⇒ `avsync` 0.0000 s).
- Relay loads disable mpv's network read timeout for the loopback stream only;
  an intentional pause can leave it silent indefinitely. The external HTTP
  demuxers keep the ordinary timeout, which a 90-second pause survives.
- Never pass `start=` to place relay playback. `rebase-start-time=no` means
  the stream's absolute Matroska PTS already position it.
- Keep the dedicated blocking downlink and loopback threads. No media packet
  may cross the Compose/coroutine UI path.
- The pre-mpv queue is bounded by bytes (256 MiB), mpv's forward cache by
  bytes (128 MiB). Backpressure must stop producers, never grow memory.

- **Picture-in-Picture never stops the Activity**, so `ProcessLifecycleOwner`'s
  `onStart`/`onStop` do not see it. Anything that has to react to the player
  going away belongs on the metrics loop or the Surface callbacks, not on a
  lifecycle callback. MediaCodec cannot decode without its output Surface, so
  while the player is away video runs ahead of the audio clock — 24.9 s after
  twenty seconds in PiP — and mpv cannot close that by seeking. The drift
  watchdog in `handleWatchdogs` reloads the epoch at the audio position
  instead; it is bounded by a cooldown because the gap reopens for as long as
  the Surface is gone.

## Known issues

- Seeking while paused reached `SEEKING -> PAUSED`, which `SessionStateMachine`
  rejected, failing the session (fixed 2026-07-26 by allowing it). Any new
  terminal state a controller path can produce needs a matching edge there.
- The drift watchdog can fire repeatedly during a long PiP session (once per
  cooldown) because the Surface is still gone. Switching the model to
  `passthrough` for PiP does not help: the repro was *on* passthrough and the
  gap opened all the same. Reacting to PiP entry/exit would need a signal from
  `MainActivity`, which does not exist yet.
- PGS/VobSub bitmap subtitle rendering is still unverified — the test library
  has only SSA samples.
- S Pen and Samsung DeX interactive smoke tests remain hands-on.
