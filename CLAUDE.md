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
  loopback server, session state machine, failure taxonomy.
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
- **Debug builds always carry the debug icon.** `app/src/debug/res/drawable/
  ic_launcher.xml` (amber, hazard-striped corner) shadows the navy release
  icon in `app/src/main`, so a debug install is recognisable on the tablet at
  a glance. Never give a debug build the release icon: do not delete or
  bypass that file, do not point the manifest's `android:icon` at a different
  drawable for debug, and when the release icon changes, redraw the debug
  variant alongside it rather than letting the two converge. This holds for
  co-installed (`-PcoinstallDebug=true`) builds too.
- **One debug build on the device, ever. Always overwrite the one that is
  there; never install a second.** The debug build's home is
  `org.upscalerelay.android.debug`: build device installs with
  `-PcoinstallDebug=true` (the device tests refuse any other id) and install
  with `adb install -r`, which replaces the debug build already there. Before
  any install, look at what is on the device:
  `adb shell pm list packages org.upscalerelay`, then
  `adb shell dumpsys package <id> | grep pkgFlags` for each app package. An
  app is a debug build when its flags include `DEBUGGABLE`, whatever its
  application id; never infer "release" from the id alone. A debug build
  found under any other id (the base id, a leftover suffix) is uninstalled,
  not kept alongside, and a temporary `applicationIdSuffix` is never
  invented to get around an install problem. If `install -r` is refused
  (signature mismatch), uninstall the old debug build and install again. The
  instrumentation APK (`<applicationId>.test`) follows the same rule: one,
  matching the installed debug build, with any stale one uninstalled. On
  2026-10-09 the tablet carried two amber icons because a debug build at
  `org.upscalerelay.android` had been taken for the release app and a second
  debug build was installed beside it as `.debug`.
- Debug and release share an `applicationId`, so a debug build cannot go
  over a signed release. `-PcoinstallDebug=true` adds the `.debug` suffix so
  the one debug build can sit beside a signed release and leave its data
  alone. That is all the flag is for; it never licenses a second debug
  build.
- **Data in a debug build is disposable.** Settings, watch history, the
  library cache and anything else a debug install holds (the amber icon is
  how you know it is one) can always be cleared, overwritten or lost to an
  uninstall for the sake of a test — `pm clear`, reinstalling, marking files
  watched, changing the host — without asking and without preserving it
  first. **Never back up, copy aside, export or otherwise try to preserve a
  debug build's data**, not before overwriting it and not before
  uninstalling it, and never choose one approach over another because it
  keeps that data. There is no need to build a second throwaway copy just to
  protect a debug install's data. This never extends to the signed release
  app: its data is the user's and is not touched.
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

- **For local or confirmed-external relay loads, attach the original media
  after playback starts, never on `loadfile`.**
  mpv positions an external demuxer at the current playback time *when the
  track is selected*, and during load that time is zero. The relay stream
  carries only the tail of the file from the seek target, so `audio-file` /
  `sub-files-append` left the audio and subtitle demuxers at the start of the
  original file and mpv reached the epoch by decoding everything before it —
  13–20 s of black screen after a far seek, scaling with the seek target.
  `MpvPlayerEngine.attachExternalMedia` adds them with one `audio-add` on the
  first `PLAYBACK_RESTART` instead.
- **Never `audio-add` a confirmed muxed epoch.** Only
  `session_opened.aux_tracks == "muxed"` authorizes omitting `/media`; a
  request is not confirmation. Muxed reloads re-enumerate fresh Matroska
  tracks and remap explicit choices by descriptor/occurrence, including
  subtitles-off. Numeric mpv IDs are not protocol identity.
- **Cached attachment confirmation is all-or-fail.** Materialize the complete
  verified font view before `loadfile`; never silently add `/media` after the
  server omitted attachment bodies. Bearer tokens belong only in the
  Authorization header and must never enter URLs, DataStore, telemetry,
  ordinary logs, or exception text. Cancellation must close and join the
  response writer before deleting its temp/view or tearing down the session.
- `--start=<target>` on the load does not fix the first rule's problem (the
  external demuxers left at zero) and was tried on the device: the
  loopback stream is a live one-shot socket, so mpv rejects the seek
  (`Cached seek not possible` / `Cannot seek in this stream`). A back buffer
  and `demuxer-seekable-cache=yes` do not help either — the epoch carries one
  keyframe, so there is no cached range to seek within.
- **The epoch loads `pause=yes`** (`relayLoadOptions`). Without it the picture
  runs on alone for the second the attach takes, and mpv reconciles that drift
  against a freshly started audio track: an A/V desynchronisation warning and
  tens of dropped frames on *every* stream start. The hold is lifted, with the
  caller's real pause intent, on the first `PLAYBACK_RESTART`: by
  `attachExternalMedia` once the added tracks are in place, and by
  `completeMuxedRestart` for a muxed epoch or a source with no auxiliary
  tracks, after it has re-applied the track choices. A load path that reaches
  neither leaves playback on its first frame.
- Adding a track mid-playback needs the `select` flag; `auto` only marks the
  file as a candidate and leaves it unselected (verified — it produced no
  audio at all). Explicit user track choices are remembered in the engine and
  re-applied after each attach so a seek cannot revert them.
- **One external add, never a matching second add.** Confirmed subtitle-only
  sources use one delayed `sub-add` with no audio-ready wait. Confirmed sources
  with no auxiliary tracks use no external attach. Missing source metadata
  retains the single `audio-add` compatibility path. mpv exposes *every* track
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
- Relay loads set `network-timeout=0` (`relayLoadOptions`): an intentional
  pause can leave the loopback stream silent indefinitely, and the ordinary
  10 s would end the file. The option is per file, not per stream. While a
  relay file plays mpv's `network-timeout` is 0 for everything (measured on
  the device 2026-10-09: 10 idle, 0 during the relay file, 10 after close),
  so the external demuxer that `audio-add` / `sub-add` opens during that file
  is opened under 0 as well. Nothing gives it "the ordinary timeout", as this
  rule used to say: do not count on mpv to time out a stalled `/media` or
  local-bridge read. (A 90-second pause and resume did keep external audio
  alive on the device.)
- Never pass `start=` to place relay playback. `rebase-start-time=no` means
  the stream's absolute Matroska PTS already position it.
- Keep the dedicated blocking downlink and loopback threads. No media packet
  may cross the Compose/coroutine UI path.
- The pre-mpv queue is bounded by bytes (256 MiB), mpv's forward cache by
  bytes (128 MiB). Backpressure must stop producers, never grow memory.
- **No replacement session until the server has confirmed the previous one
  released.** Teardown waits for the server's `closed` acknowledgement; a
  state notification, EOF or timeout is not confirmed native cleanup. When the
  control connection is already dead (a tablet that slept, Wi-Fi that dropped)
  that acknowledgement cannot come, and treating its absence as the hard stop
  ended playback with "Server did not confirm resource release" on every
  wake from a long sleep. `disposeController` now records the session
  (`unconfirmedRelease`) and every session open first runs
  `confirmPriorRelease` on the new connection: `GET /status` has to stop
  listing that `session_id` with `restart_required` false
  (`RelaySessionController.awaitReleased`, bounded at 45 s). The server
  delists a session only after its native close has returned and sets
  `restart_required` when that close failed, which is what makes this a
  confirmation and not an assumption; on the device the wait is real, 16 s
  until the server's heartbeat noticed the dead socket. Still listed at the
  deadline, `restart_required`, a `/status` that cannot be read, and a lost
  connection whose session id was never learned all remain the hard stop
  (`cleanupFailure`). An unreachable server settles nothing and the question
  stays open for the next connection. Any new place that opens a session must
  call `confirmPriorRelease` first.
- Stop and await the player's command queue before retiring loopback,
  external-media or font owners. Native initialize/destroy run off Main and
  preserve the process-global JNI ownership barrier.
- Seek inactivity is extended only by advancing subtitle-index coverage for
  the current epoch. `seek_ready` acknowledges the flush, not playable media.

- **Read where the user is with `RelayUiState.userPositionSeconds()`**, never
  `mpvMetrics.positionSeconds`, for anything that moves relative to it or
  resumes from it (skips, chapter steps, reconnect, settings restart, the
  hand-over to the original). Every seek reloads the stream, and from the
  reload until the new epoch plays mpv's reported position is the stale old
  one and then zero. Reading it turned a second "back 1:25" into a jump to
  0:00. Only end-of-file checks want mpv's real position.
- **The picture always runs full size under a display cutout.** Never shrink
  the player, pad the picture, or reduce the size negotiated with the server
  for one: on the tablet that costs 28 rows, and the owner rejected it
  outright (2026-10-09). Only the controls mind a cutout, and the top bar
  does not avoid one in the top edge (`playerControlInsets()`): its title and
  buttons sit at the two ends, and padding the whole bar down by the cutout's
  height left it hanging below a strip of bare picture. The sides and the
  centred bottom controls still keep clear of one. Android would not keep an
  app out of the cutout anyway: `LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER` is
  ignored for an app targeting SDK 35+ on Android 15+ (verified on Android
  16).
- **Each player control owns its interaction source.** A shared
  `MutableInteractionSource` draws one press on every control it was given
  to. Use `rememberPressReporting(shared)`: the control shows its own press
  and forwards it, so the auto-hide timer still sees it.
- **A press that misses a control must never reach the picture.** The gesture
  layer under the controls is hit directly everywhere, and a direct hit beats
  an icon button's 48dp minimum touch target. So a button was hit only inside
  its 40dp circle (70 px, 6 mm on the tablet), and a press on its edge counted
  as a tap on the picture: the controls vanished and the button then took two
  more presses. Found on 2026-10-09 with `adb shell input tap` while chasing
  the lock report below, which it did not explain. The control bars take
  their own presses (`absorbMissedPresses`), which also gives the buttons
  their minimum target back; keep it on any bar, and keep controls inside a
  bar that has it. The lock, in both states, is a `LockButton` whose target
  runs out to the screen corner. Locked, a press on the picture only brings
  the unlock button up and restarts its timeout; it never takes it away.
- **The tablet cancels ordinary presses on the player's top bar as palms, and
  `PalmCancelRescue` gives them back.** In landscape the Tab S9 Ultra's touch
  controller raises its palm flag on fingertip presses in the top 9 mm of the
  screen: 22 of 44 presses on the lock (2026-10-09), all at y <= 85 px, none
  of the seven at y >= 91 px. The system then delivers `ACTION_CANCEL` with
  `FLAG_CANCELED` and, 14-17 ms later, the `ACTION_UP`; the button lights up
  and nothing happens ("the lock takes several presses"). The top bar's
  buttons sit in that strip because the bar starts at the top edge, under
  the notch, and the owner chose to keep it there and accept those presses
  rather than move the controls down. `MainActivity.dispatchTouchEvent` holds
  such a cancel and delivers the up in its place, only in the player, only in
  the top 64dp, only for one stationary pointer whose up follows within
  50 ms. Do not widen any of those: a palm that really rests there keeps
  producing cancels and is still rejected. Scripted input cannot show this
  bug (`input tap`, Compose tests and UiAutomation never pass through the
  touch controller), so "works with injected taps" proves nothing about
  presses near a screen edge. Diagnose with real fingers:
  `adb shell getevent -lt /dev/input/event<N>` shows `EV_KEY 0118` (Samsung's
  BTN_PALM) and logcat shows `InputReader: Btn_palm` when it happens.

- **A loading overlay in the browser is only for a wait the user asked for.**
  Connects the app makes on its own — after leaving the player
  (`closingJob`), after a wake with a dead socket, on a cold start behind
  the cached listing (`files/library-cache.json`, `backgroundConnectJob`) —
  pass `visible = false` to `connectInternal` and keep the listing on screen
  (`keepLibrary`). Because the controls stay enabled meanwhile, anything
  that needs the server must get its controller from `connectionForAction()`
  (or run through `libraryAction`), never from the `controller` field
  directly: that is what waits out the connect in flight.
- **Reconnects are made on demand, never on a timer, and there is no setting
  for them — nor for connecting at launch, which always happens, quietly.** No retry loops, attempt counters or backoff. A connection is
  re-made when something makes it worth trying: the user needs the server
  (`connectionForAction`, which may retry a failed request once on a fresh
  connection), a live connection was seen to die, the app came to the
  foreground, or a network appeared (`reconnectQuietly` in the browser,
  `resumePendingPlayback` in the player). The automatic ones are `quiet` and
  never report; only a connect the user asked for may show an error, and the
  next successful connect removes it (`connectionError`). A connect that
  never got through must not trigger another — that is the chase the
  `established` flag in `collectController` prevents. `awaitNetwork` holds a
  connect until the radio is back after a wake instead of failing into it.
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
- A local source that fails mid-read ends playback early with no error.
  MediaExtractor reports a read error the same way as end of file
  (`sampleTime < 0`), so `ExtractorPacketReader` returns null and the uplink
  sends EOS. A provider that drops out (network storage, a cloud document)
  looks like a short file. Telling the two apart would need a heuristic, such
  as an end far short of the declared duration, which risks false errors on
  files with wrong duration headers, so it is deliberately unhandled
  (decided 2026-10-09).
- Pixel aspect for local uplinks comes from the container: MediaExtractor for
  MP4, `MatroskaVideoAspect` for Matroska display sizes. An aspect carried
  only in the video bitstream (Matroska `DisplayUnit` 4) is left to the
  server, which reads it from the uplink's codec parameter sets when
  `open_session.video.sample_aspect_ratio` is absent.
