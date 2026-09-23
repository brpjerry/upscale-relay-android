# Android codebase audit — 2026-09-22

Audit branch: `fix/android-audit-2026-09-22`, based on Android `694efb2`
(v0.18.1). Server contract: `dcbb20d2c5df8698ba59719844b636ad7a75a187`,
the latest `fix/codebase-audit-2026-09-22` revision verified against origin.
The installed relay at `192.168.0.115:8590` was used for passthrough device
checks. The [handoff](RELAY_AUDIT_HANDOFF.md) supplied the initial regression
targets; its pre-v0.18.0 observations are historical.

## Review scope

Reviewed protocol parsing/framing; control and session orchestration;
downlink, queue, loopback and uplink ownership; attachment caching;
SAF demux/HTTP/browser/chapter paths; mpv/JNI lifecycle and track selection;
Compose UI, Activity/PiP and media service; preferences, backup, discovery and
logging; build/bootstrap and release workflows. Fixes target concrete failure
paths rather than a broad architecture rewrite. Large ViewModel/UI files
remain a maintainability concern; splitting them is separate work.

## Findings addressed

| Priority | Trigger and previous behavior | Change |
| --- | --- | --- |
| High | Teardown waited 100 ms, then closed the socket; replacement sessions could start while server native owners remained alive. | Wait for the actual `closed` message for up to 30 seconds. EOF, `state:closed`, timeout and native failure do not count as success. Cleanup failures remain sticky; automatic recovery stops on unconfirmed cleanup or `server_restart_required`. Local cleanup always runs. |
| High | First media and post-seek waits disagreed; long subtitle indexing could time out, while a dead post-ack seek could wait indefinitely. | One controller-owned 60-second inactivity policy covers initial rendering and seeks, including paused seeks. Only strictly advancing subtitle coverage for the active epoch extends it. Expose progress text without inventing a percentage. |
| High | External source attachment was unconditional, including video-only/subtitle-only sources. | Parse nullable source-presence fields; honor confirmed muxed mode first, omit external media for confirmed absence, and use one delayed `sub-add` for confirmed subtitle-only sources. Unknown metadata retains compatibility behavior. |
| High | Native external attachment could outlive stop/load and apply state to a new file; destroy could hold the callback lock while joining the native event thread. | Serialize load/stop/add/destroy commands, use generation checks and an awaited stop barrier, and destroy outside the callback lock. Serialize process-global JNI ownership across asynchronous Activity cleanup. |
| High | Canceling initial open did not retire its continuation; settings/reconnect reset pause and track intent; native/network cleanup blocked the main thread. | Track/cancel/join opening work, propagate cancellation, preserve intent on same-file restart, publish temporary resources before IO dispatcher returns, and perform bounded owner cleanup on IO. |
| Medium | Audio/subtitle IDs can overlap; selecting subtitle ID 1 could remember audio ID 1 and misapply it after a muxed reload. | Scope remembered selections and preference lookup by track type; preserve subtitles-off and reject stale track IDs. |
| High | Uplink batches copied up to 16 arbitrarily large packets; a timed-out worker could overlap the next epoch; cancellation could discard a buffered packet tail. | Stream framing without full payload copies, flush at a byte threshold, keep one writer, close late readers, and finish an already-started packet before handing the stream to a new epoch. |
| Medium | Zero-byte packets bypassed the queue byte limit; socket creation/accept could race close. | Add an item cap alongside byte backpressure; publish sockets before blocking connect and close late accepted sockets. Keep EOS scoped to its epoch. |
| Medium | Shared font-cache instances could evict another session's active objects; canceled materialization could leave a view or temporary file. | Protect active views/objects across instances, reserve bounded storage, and reclaim canceled results. Avoid newer Java Stream APIs unsupported by the minimum Android version. |
| Medium | Local HTTP headers/worker count/ranges were insufficiently bounded; loopback access used a predictable path. | Bound clients and headers, validate ranges, use an unguessable session path, close active transfers, and improve extractor descriptor/cancellation ownership. |
| Medium | Malformed bearer header exceptions could expose a token; corrupt saved positions or backup record delimiters could poison history. | Validate bearer tokens before constructing headers with generic errors; reject nonfinite positions and record delimiters. |
| Medium | Discovery callbacks after stop could repopulate stale servers; opt-in log setup/pruning/close ran on Main. | Guard discovery generations and listener identity, bound discovery entries, and serialize logging IO off Main. Roll back failed log creation and bound queued line lengths. |
| Medium | Manual release dispatch could build the selected branch and publish it under a different requested tag; interrupted bootstrap left an unverified runnable JAR. | Check out and verify the requested version tag. Verify temporary wrapper downloads before atomic publication. |
| Medium | PiP switched Compose branches and recreated the Surface; narrow overlays and scrub controls lacked robust layout/accessibility behavior. | Retain one Surface, use actual window size, add safe insets/compact controls/wrapping actions, expose seek semantics and a 48 dp target, honor accessibility timeouts, and use the current position when scrubbing starts. Held pause keys now act once. |

## Validation

Host commands use the installed Gradle 9.7.1, JDK 17 toolchain and Android SDK
37. The native libraries come from the existing pinned, SHA-256-verified mpv
APK. The co-installed debug build uses `org.upscalerelay.android.debug`; the
installed release app and its saved data are retained.

```sh
ANDROID_HOME="$HOME/Android/Sdk" gradle --no-daemon -PcoinstallDebug=true \
  :relay-protocol:test :relay-client:test :relay-demux:testDebugUnitTest \
  :player-mpv:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Regressions cover wire length boundaries/rejection before reading bodies,
fragmented signed timestamps, old/new JSON defaults, source-mode selection,
delayed/missing `closed`, EOF/native failure, cancellation and stale seek
replies, advancing/stale/repeated subtitle progress, token error redaction,
zero-byte queue pressure, EOS followed by another epoch, late reader cleanup,
uplink byte thresholds and interrupted packet tails, font-cache active views
and canceled downloads, HTTP ranges/headers, malformed NAL lengths, track
identity, backup corruption and coroutine cancellation.

Device: Galaxy Tab S9 Ultra (`SM-X910`), Android 16/API 36, build
`X910XXS6EZH3`. The first live regression passed with server-library H.264/FLAC
source, `passthrough`, `hevc-qp18`, HEVC MediaCodec output, muxed tracks and no
external track duplication. It exercised initial play, subtitles-off, paused
seek, paused settings restart, overlapping paused seeks, resume and acknowledged
Stop/return to library. The native Activity close/reopen regression also passed.
Captured player and library screens were inspected for overlap, legibility,
insets and touch layout. A settled sample reported zero decoder/output drops,
no cache starvation and near-zero `avsync`; this short sample is not endurance
evidence. After the run the server reported zero sessions and
`restart_required:false`.

The full host run passed **103 tests** (20 protocol, 45 client, 9 demux,
11 player, 18 app), lint with no errors, and both debug APK builds. Lint still
reports dependency-update/platform-policy suggestions and minor legacy hints;
these are not all resolved by this audit.

Five device regressions passed: truncated framing; deterministic native-owner
handoff; repeated Activity close/reopen; live server paused seek/settings restart;
and local uplink/seek/direct fallback. The final combined run passed all five in
30.512 seconds. The relay ended with zero sessions and no restart requirement;
the debug app's font-session directory was empty. The tablet's original
stay-awake setting was restored after testing. The local fixture was a generated
20-second H.264/AAC MP4 in the debug app's private files. Direct-original pause
and seek worked immediately. This exercises the real extractor and HTTP bridge
through a local file URI; it does not certify arbitrary SAF providers.

Run device tests after installing the co-installed APKs with
`adb shell am instrument -w -r -e class <test-class> ...debug.test/androidx.test.runner.AndroidJUnitRunner`.
`AuditPlaybackDeviceTest` accepts `auditHost`, `auditPort`, `auditMediaPath`
(server library path), and `auditLocalFile` (fixture basename in the debug
app's files directory). Live tests skip when their fixture argument is absent.
`NativeLifecycleDeviceTest` and `MediaFramingDeviceTest` need no relay fixture.

## Remaining limits and follow-ups

- Initial SAF metadata queries and native/provider reads are not guaranteed
  forcibly interruptible by every document provider. Per-epoch readers have
  explicit cancellation and a retiring writer cannot overlap its replacement;
  a hostile provider can still delay retirement. Test network/cloud providers.
- Android MediaExtractor can omit streams that mpv understands. Local audio
  presence is affirmative when observed; absence remains unknown until a
  complete container probe can establish it. Do not silently drop unsupported
  local audio/subtitles by guessing that they are absent.
- Native HTTP attachment is serialized and uses mpv's finite network timeout;
  pathological native calls can still outlive the command wait. The client
  refuses unsafe replacement rather than force-destroying an in-flight owner.
- Long-duration, 20-cycle leak, thermal/battery, S24/compact physical hardware,
  S Pen/DeX and PGS/VobSub gates from [device notes](ANDROID_DEVICE_NOTES.md)
  remain distinct acceptance work. Server-side subtitle-spool deletion and
  GPU-worker health are governed by the server's acknowledged cleanup contract;
  `/status` alone is not proof of filesystem/native deletion.
- Cleartext LAN transport, arm64-only native binaries and target API 36 remain
  intentional existing constraints. Dependency-update lint suggestions are
  not evidence that an untested blanket upgrade belongs in this audit.
- PowerShell bootstrap execution and GitHub release publication were not run.
  POSIX bootstrap behavior was checked with successful, corrupt and interrupted
  download simulations; no release was published during validation.
