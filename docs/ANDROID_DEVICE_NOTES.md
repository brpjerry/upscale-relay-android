# Android device validation notes

## Matroska chapters and TensorRT loading results — 2026-07-19

The release-candidate build was installed on the target Galaxy Tab S9 Ultra
(`SM-X910`, Android 16) and tested against the matching Python server from a
fresh TensorRT cache.

- A first-use `2x_AnimeJaNai_HD_V3Sharp1_Compact` engine build kept the control
  connection alive and displayed the indeterminate loader plus the server's
  elapsed-time message. The session transitioned from `OPEN` to `PLAYING`
  without a timeout and used `TensorrtExecutionProvider` with the uint8-wrapped
  model.
- A server-library MKV exposed all six embedded chapter titles and timestamps.
  Selecting `Lucky Channel` created epoch 1 and settled at 1203.6 s with
  0.024 ms A/V offset; the dedicated next-chapter control created epoch 3 and
  landed at 1316.6 s in the 1311.9 s `ED` chapter. Both samples reported zero
  decoder drops.
- A generated local SAF MKV exposed all three embedded titles (`Cold Open`,
  `Middle`, `Finale`) through the uplink-session echo. Selecting `Middle`
  created epoch 1 and played from 8.23 s after its 5.00 s mark with zero
  decoder or output drops.
- The temporary 14 MB SAF fixture was removed after the run and every session
  tore down cleanly.

Status: **Phase 1 acceptance passed; Phase 2 core, Phase 3 tablet-shell,
Phase 4 local-file, Phase 5 discovery/recovery, and Phase 5.5 device gates
passed, including the phone/small-screen layouts on a Galaxy S24 Ultra and
watch-position resume. Outstanding: S Pen/DeX interactive smoke and
PGS/VobSub validation for lack of a bitmap-subtitle sample**

The implementation lives in the repository root. The
first set of results covers the Phase 1 gate in
[`ANDROID_CLIENT.md`](ANDROID_CLIENT.md#acceptance-gate): server-library video,
protocol v1, lossless HEVC, hardware decode, bounded transport, lifecycle, and
failure handling. The Phase 2 results below cover synchronized original audio,
SSA subtitles, controls, epoch seeks, the original two Android HEVC choices, and fit/cover on
the same target tablet.

## Phone-layout and resume results — 2026-07-17 (Galaxy S24 Ultra)

Device: `SM-S928U1`, Android 16 / API 36, 1080×2340 at 420 dpi (411 dp
width → compact). The phone keeps its fingerprint lock, so screen-lock
scenarios were exempted from this pass.

- The compact shell rendered a bottom navigation bar with the Material icon
  set, a full-width connect card, and the discovered `upscale-relay` entry;
  one tap connected.
- The SAF folder grant flow ran on the phone's picker; the Movies tree
  listed the phone's own MKVs (dot-folders filtered).
- Local relay playback of an HEVC source episode reached PLAYING through
  `mediacodec` (the HEVC length-prefixed → Annex B conversion path live on
  device), with keyboard ±10 s epoch seeks working.
- The single-pane server browser opened its detail full-screen with
  "← All videos"; a server-library file played with the phone-negotiated
  1920×1080 output; landscape player chrome fit without clipping.
- **Watch-position resume** (both sources): playing to ~64 s locally,
  backing to the library (server sessions dropped to zero), and reopening
  resumed near the saved position (76.7 s after ~13 s of playback);
  a server file saved at ~31 s reopened to 41.9 s after ~12 s. Positions
  save on a five-second cadence, clear on completed playthroughs or within
  90 s of the end, and resume through an epoch seek before mpv attaches.
- One adb transport drop occurred mid-run (device reported offline) and
  recovered with an adb server restart; playback on the phone was
  unaffected.

## Phase 5.5 device results — 2026-07-17

Same tablet and server. The local SAF episode carried all runs; phone
layouts were exempted from this pass at the owner's direction.

- **Media notification / lock-screen controls:** the MediaSession registered
  as the system media-button target with correct metadata and live position;
  the MediaStyle notification posted on the `playback` channel.
  `KEYCODE_MEDIA_PAUSE`/`PLAY` paused and resumed both mpv and the relay
  session with the position frozen while paused.
- **Screen-off background playback:** 30 s with the screen off left the
  session PLAYING on both ends with telemetry live throughout and position
  advancing at 1×. Two fixes came out of this test: the foreground service
  now holds a partial WakeLock plus WifiLock (Samsung's standby Wi-Fi
  power-save otherwise killed the sockets in ~30 s), and the service no
  longer stops itself when its first snapshot races the first metrics tick.
  On wake, video re-attached on hardware decode and caught back up to the
  audio clock over ~20 s (0 decoder drops), then A/V error returned to
  microseconds.
- **Picture-in-Picture:** Home auto-entered a chrome-free PiP window at the
  downlink aspect ratio with subtitles rendering; playback advanced exactly
  1× in PiP and the intent-relaunch expanded back to the full player.
- **Interpolation preferences:** with display-resample + motion
  interpolation enabled live from Settings, hardware HEVC decode was
  retained, an epoch seek completed cleanly, external-audio sync measured
  ~25–38 µs, and a 20 s steady-state window recorded zero output drops.
- **Keyboard:** Space toggled pause and DPAD_RIGHT drove a +10 s epoch seek
  (avsync ~10 µs afterwards), via adb key events.
- **UI pass observations:** icons render across rail/lists/transport; the
  restored last destination appears without intermediate flashes; the
  Phase 4 "Back from ENDED exits the app" mystery was reproduced from the
  crash buffer as a LazyColumn duplicate-key crash (same URI in the tree
  listing and recents) and is fixed by namespacing keys.
- **Automation note:** blind coordinate taps during these runs nudged some
  user preferences (downscale filter, diagnostics overlay, video-sync
  toggles); preferences were reset afterwards but are worth a glance.

## Phase 5 device results — 2026-07-17

Same Galaxy Tab S9 Ultra and TensorRT server host. The local Grow Up Show
episode (SAF uplink source) carried all recovery tests.

### Discovery

The server advertised `_upscalerelay._tcp` (zeroconf, TXT: protocol, media
port, server name). The tablet's connect panel listed
`upscale-relay on <hostname>` with `192.168.0.115:8590` next to the untouched
manual fields, and one tap connected and browsed as usual.

### Ten-second interruption resume (acceptance gate)

With local relay playback at 62.8 s, Wi-Fi was disabled for 10 s via adb. The
recovery overlay appeared over the paused frame — "Network connection lost —
Reconnecting, attempt 3 of 6. Playback resumes where it stopped." with
**Stop trying** and **Play original** — and playback resumed automatically
about 15 s after Wi-Fi returned (Wi-Fi reassociation included), position-
continuous, `auto_resume_count=1`, then advanced at exactly 1× with zero
decoder drops. Pausing the player at failure detection also fixes the Phase 4
observation that bridge-served audio kept playing under the failure card.

### Mid-play settings changes

- Model change (Compact → fp16) from the active player's sheet reopened the
  session at 169.4 s and returned to PLAYING in about 5 s with the new model
  in session metadata; the sheet stayed open and reflected the change.
- Quality change (hevc-qp2 → lossless-hevc) reopened at 246.4 s in about 4 s.
- Selecting `realesrgan-x2-ts` (an architecture-bound model that cannot reach
  realtime) made the restart outrun its first TensorRT engine build; the
  failure rolled into the reconnect loop, which recovered on its own once the
  engine finished — and the session then rebuffered repeatedly, raising the
  live sustain warning: "The server is not keeping up with realesrgan-x2-ts
  at lossless-hevc…" with a working Dismiss action.

### Defects found and fixed on-device

1. Warm-up buffering right after a session start or restart counted as
   rebuffer events and produced a spurious sustain warning for a healthy
   model; rebuffer accounting now requires steady state (PLAYING and more
   than 15 s into the session).
2. With the server fully gone (Windows Firewall silently drops SYNs once the
   allowed program exits, so connects burn the full 15 s timeout), recovery
   looped at "attempt 1 of 6" forever. Two stacked causes, both fixed:
   `TimeoutCancellationException` extends `CancellationException`, so the
   reconnect loop treated its own attempt's network timeout as being
   cancelled and died silently — relay-client timeouts now surface as
   `SocketTimeoutException` at the API boundary (which also fixes a latent
   stuck-`seeking` path on seek timeouts) — and the dead attempt's trailing
   failure event then armed a brand-new loop, which an exhaustion latch now
   prevents until a user action or a successful session resets it. Budget
   exhaustion now surfaces the classified failure card.

The stall watchdog (15 s of a starved player with no downlink bytes) fired
once under the starving realesrgan session and fed the same resume path
(`auto_resume_count=2` at that point), confirming the media-stalled taxonomy
end to end.

## Phase 4 device results — 2026-07-16/17

The Phase 4 acceptance pass used the same Galaxy Tab S9 Ultra (`SM-X910`,
Android 16 / API 36) against the TensorRT server at `192.168.0.115`, launched
without `--library` to prove local-source sessions need no server-side media.
The test media were the two long-form MKVs in the tablet's `Movies/`
directory, both H.264 1080p Matroska at about 23.7 minutes: a Netflix WEB-DL
with AAC 2.0 (`Chainsmoker.Cat.S01E03…VARYG.mkv`, 0.91 GB) and the SubsPlease
episode with AAC plus SSA subtitles and embedded fonts (`Grow Up Show - 02`,
1.45 GB). Both files therefore exercise the length-prefixed AVC → Annex B
conversion path live.

### PTS equivalence against the Python demuxer

`Phase4PtsDeviceTest` dumped the full `AndroidMediaSource` timeline
(`pts_us,keyframe` per packet) for each file on the device; the same files
were pulled to the host and demuxed with PyAV rescaled to microseconds.

- Netflix episode: 34,094 packets; PTS sequence and order **byte-exact**.
- SubsPlease episode: 34,047 packets; PTS **and** keyframe flags byte-exact.
- The Netflix file's keyframe flags differed: MediaExtractor marked 711 sync
  samples where libav marks 1,211 container keyframes. The device set is a
  strict subset (no false keyframe was ever claimed). The server never reads
  the uplink keyframe flag (it only stamps downlink flags), and device seeks
  come from MediaExtractor's own previous-sync table, so the under-reporting
  is informational only.

The persisted `primary:Movies` tree grant satisfied the instrumentation run
directly — the test APK shares the app's uid, so no re-grant bridge was
needed after the folder was chosen once in the product UI.

### Local relay playback, seek storm, and EOS

- Local uplink playback negotiated lossless-HEVC fit at 2960×1664 through
  `mediacodec`, first with `passthrough` and then with `2x_AnimeJaNai_fp16`
  (TensorRT inference on the live local uplink). Measured A/V error stayed in
  the microsecond range with zero decoder drops.
- A 27-action rapid ±10 s storm coalesced into 14 epochs, reused one server
  session, converged to PLAYING with ~29 µs A/V error, 0/0 drops, no cache
  pause, and no stale-epoch packets. Logcat showed no extractor/descriptor
  errors and the crash buffer stayed empty — the fresh-extractor-per-epoch
  cancellation path held under stress.
- A slider seek to 23:03 played to natural EOS: `ENDED` at 1419.96 s of
  1420.11 s with 0/0 drops. The epoch-scoped uplink EOS propagated through
  the server and back. Back from the `ENDED` state currently exits the
  application instead of returning to the library (Phase 5.5 polish item);
  Back from active playback returns to the library and left zero server
  sessions in every teardown this pass.

### Direct local fallback

Killing the relay server mid-playback surfaced `Playback failed — Connection
reset` within ~4 s while the process stayed responsive. Observed behavior:
the original audio keeps playing under the failure card (it comes from the
on-device HTTP bridge, which the server's death does not affect) while video
freezes, and mpv's position follows the audio clock. **Play original** then
reloaded the bridge URL within ~2 s at that audio-clock position — continuous
with what the user was hearing, with no document picker. Direct seeks and SSA
subtitle rendering both work in fallback mode. Whether relay failure should
pause playback instead of letting audio continue is recorded as a product
decision for Phase 5.5.

### Defect found and fixed on-device

`hwdec-codecs` was pinned to `hevc`, which is correct for the always-HEVC
relay downlink but forced **software** decode when direct local fallback
played the original H.264 file. The option now allows
`h264,hevc,vp8,vp9,av1`; the re-run confirmed `mediacodec` hardware decode
for the H.264 original in fallback while relay behavior is unchanged.

### Grant persistence and recents

The `primary:Movies` tree grant survived APK replacement, process death, and
instrumentation restarts; the Local destination browses the tree and replays
recent files without reopening the picker. One cosmetic nit: a recent-file
entry whose provider hides the display name (a `downloads` provider document
from earlier testing) renders its opaque document id (`msf:…`) as the title.

## Phase 3 device results — 2026-07-16

The Phase 3 APK was installed on the same Galaxy Tab S9 Ultra (`SM-X910`),
Android 16 / API 36, against the TensorRT server at `192.168.0.115`. The server
library contained 12 episodes and advertised four models plus the original
two Android hardware-HEVC choices. The expanded bandwidth ladder was added
after this validation pass.

### Tablet shell and persistence

- The portrait Material shell rendered without clipping at 1848×2960. Its
  navigation rail, connect state, and two-pane library/detail browser were
  exercised with the real server library.
- Host/port, selected destination, and the played episode survived an APK
  replacement and process relaunch. The Recent destination reopened the file
  after reconnecting and resolving it against the refreshed library tree.
- Temporarily forcing Android night mode recreated the Activity into the dark
  color scheme. The tablet's original custom theme schedule was restored after
  capture.
- The player alone entered 2960×1848 sensor landscape and immersive mode. Its
  chrome auto-hid after four seconds, returned on tap, and left a full-bleed
  video surface with no persistent system-bar obstruction.

### Playback controls, sheets, and gestures

- Pause changed the button to Play and paused both mpv and relay state; resume
  returned it to Pause. The absolute timeline, ±10 controls, audio/subtitle
  selection, both delay controls, and the model/tier/fit settings sheet were
  reachable from the tablet UI.
- A multi-track episode exposed two audio and two SSA subtitle entries. The
  first bottom-sheet implementation put its last delay controls below
  Samsung's taskbar; both sheets are now navigation-inset aware and vertically
  scrollable. A follow-up device pass reached the subtitle-delay row above the
  taskbar.
- A horizontal drag displayed a seek preview and committed an epoch seek. The
  stream returned to PLAYING with about 9 seconds reported buffer, zero
  decoder/output drops, no cache pause, and A/V error near zero.
- Left and right vertical drags displayed live brightness and media-volume
  percentages. The scripted adjustments were reversed after capture so the
  tablet state was restored.

### Representative telemetry

The initial lossless-HEVC pass reported `mediacodec`, AAC, 2960×1664 at 23.976
fps, roughly 205 Mbps instantaneous video bitrate, about 7.5 seconds cached,
and zero decoder/output drops. A post-seek `phase3-latest.json` snapshot
reported PLAYING/PLAYING, `mediacodec`, an 80.2 Mbps current bitrate, 113.7
Mbps session-average receive rate, 9301 ms mpv cache, zero decoder/output
drops, no cache pause, 0.03 ms A/V error, and the selected
`2x_AnimeJaNai_fp16` / `lossless-hevc` / `fit` configuration.

Phase 3 is therefore accepted on the primary tablet. Smaller screens,
keyboard/mouse, S Pen, DeX, background/lock-screen media integration, and
mpv.conf-style display-resample/interpolation controls remain intentionally in
Phase 5.5 rather than incomplete Phase 3 items.

### Lossless-HEVC cover investigation and server-side fix — 2026-07-16

An exact follow-up reproduction used
`2x_AnimeJaNai_HD_V3Sharp1_Compact`, `lossless-hevc`, `cover`, and
`[SubsPlease] Grow Up Show - Himawari no Circus-dan - 02 (1080p)
[73E32C8A].mkv`. The original Android mpv policy reproducibly accumulated
28–30 output drops between roughly 2 and 5 seconds. MediaCodec reported zero
decoder drops, the mpv cache never emptied, the client received roughly
300–400 Mbps during startup, and the server remained above real time. Native
mpv logs instead showed an Android audio-device underrun while the HEVC
decoder/Surface path warmed up.

Packet-cache prefill, larger audio buffers, `display-resample`, and `autosync`
did not remove the output loss. Temporarily setting `framedrop=no` hid the
counter, but subsequent visual testing still showed the same catch-up/judder;
it was therefore rejected and removed.

The material difference was framing bandwidth. The old Cover path encoded a
3286×1848 stream and cropped the side overflow with client panscan. The exact
lossless segment briefly reached roughly 385–405 Mbps, while Fit encoded
2960×1664 and remained smooth at roughly 312–332 Mbps. The implemented fix now
center-crops Cover on the server before the final resize and emits 2960×1848
for this tablet. Off-screen columns no longer consume network bandwidth or
MediaCodec work. The final downscale filter is also selectable per server or
session. This implementation is host-tested; the exact-file physical-device
regression still needs to be repeated against the new server and APK.

## Phase 2 device results — 2026-07-16

The device was a Galaxy Tab S9 Ultra (`SM-X910`) on Android 16 / API 36,
connected as `192.168.0.51` for this run. The server remained the Ryzen 7
9800X3D / RTX 5090 TensorRT host at `192.168.0.115`. Original audio and
subtitles came from the Range-capable server `/media` URL; the upscaled video
continued over the framed media socket and per-epoch localhost mpv input.

### A/V, decode, and tier results

| Tier / mode | Output | Observed transport | Result |
|---|---:|---:|---|
| `lossless-hevc` / fit | 2960×1664 at 23.976 fps | commonly above 200 Mbps | More than 30 aggregate minutes, natural EOS, `mediacodec`, AAC clock, no cache rebuffer, zero decoder drops |
| legacy `visually-lossless` (now `hevc-qp18`) / cover | 3286×1848 at 23.976 fps | about 15 Mbps in the startup sample | `mediacodec`, AAC and SSA attached, zero decoder drops, normal startup |

The lossless run's measured `avsync` stayed near zero; representative samples
were about 0.02–0.03 ms and remained aligned after seeks. Output scheduling
drops accumulated slowly during long playback, while decoder drops and cache
rebuffer stayed at zero. This matches the Phase 1 observation that the residual
counter is display scheduling rather than decode or transport loss.

### Controls and epoch seeks

- Pause set both mpv and the server session to paused; resume returned both to
  playing without a reload or sync jump.
- Stop, ±10-second controls, the absolute position slider, audio/subtitle track
  cycling, and ±0.1-second audio/subtitle delay controls were exercised on the
  physical UI. Both delay properties reported `0.1` seconds after adjustment.
- A 25-action rapid seek storm reused one server session, converged on epoch
  25 and the final requested target, dropped stale epochs, and produced no
  deadlock or controller failure. Final audio/video error was about 0.03 ms.
- A normal +10-second seek returned to playing in about 5 seconds. A seek from
  roughly 2:15 to 16:15 returned in about 13 seconds. The final reload after
  the deliberately abusive storm took about 28 seconds; seek latency remains
  a polish target, but correctness and bounded completion passed.

The downlink socket remained attached across epoch changes. Each epoch used a
new byte-bounded queue and localhost listener, and mpv reloaded without a
`start=` option so absolute Matroska PTS continued to place video against the
original audio clock.

### Subtitle coverage

The acceptance episode contains one SSA subtitle stream (`ssa`, English) and
embedded fonts. It rendered through libmpv, appeared as `en · English subs`,
cycled to `Subs off` and back, and accepted a live +0.1-second delay. A
read-only scan of all 12 files in the configured library found SSA subtitles
only. PGS/VobSub rendering is therefore not claimed; it is the sole remaining
Phase 2 device-gate sample.

### Phase 2 defects found and fixed on-device

1. Android mpv rejects `audio=yes`; the client now uses `audio=auto`.
2. mpv's `sub-files` path-list parser split HTTP URLs at colons. The load uses
   the single-item `sub-files-append` option with fixed-length outer escaping.
3. Rapid reload initially let mpv close the old localhost socket before the
   controller marked that disconnect as expected. Reload now stops mpv,
   immediately retires the old queue/listener and changes epoch, waits 150 ms,
   then loads the replacement stream.
4. The lowest control row was drawn beneath Samsung's landscape taskbar. The
   overlay now applies navigation-bar insets; its controls end at y=1746 and
   the system region begins at y=1764 on the test tablet.

### Phase 2 decision

- A/V endurance, seek correctness/stress, SSA selection/delay, controls, both
  hardware-HEVC tiers, and fit/cover: **passed**
- PGS/VobSub device rendering: **pending sample availability**
- Shared-mount path mapping remains deliberately unimplemented, as planned.
- The proven core is ready for Phase 3 tablet UI work without a protocol or
  decoder architecture change.

## Final Phase 3 host build — 2026-07-16

- Debug APK: `android_client/app/build/outputs/apk/debug/app-debug.apk`
- Size: 49,663,382 bytes
- SHA-256: `2009aa8595c234d4bfbfc5f34e4825ae8ce666eada189151f31bdb8260728d9b`
- Build type / packaged ABI: debug / arm64-v8a only
- Packaged native player: `libmpv.so`, `libplayer.so`, and matching FFmpeg
  dependencies from the pinned mpv-android artifact
- mpv-android commit: `3018d47`; native release artifact: 2026-04-25
- Kotlin/JVM tests: 17 unique tests passed, 0 failed (20 Gradle test
  executions because variant-independent app and player tests run for debug
  and release)
- Android device instrumentation: 1 passed, 0 failed on `SM-X910` / API 36
- Existing Python regression suite: 29 passed, 0 failed
- Android lint, debug assembly, physical install: passed

The final APK byte size and SHA-256 below are generated after the final build
and are the identifiers for the accepted artifact.

## Device, server, and negotiated output

- Device: Samsung Galaxy Tab S9 Ultra (`SM-X910`, serial `R52W60E5RLP`)
- Android: 16 / API 36
- Build fingerprint:
  `samsung/gts9uwifixx/gts9uwifi:16/BP4A.251205.006/X910XXS6EZF1:user/release-keys`
- Server: Ryzen 7 9800X3D, RTX 5090, TensorRT execution provider
- Server / tablet IP: `192.168.0.115` / `192.168.0.164`
- Library root: Windows UNC share supplied to `relay-server --library`
- Source: `[SubsPlease] Grow Up Show - Himawari no Circus-dan - 02 (1080p)
  [73E32C8A].mkv`, 1420.1 seconds, 23.976 fps
- App Surface: 2960×1848 landscape; encoded video: 2960×1664, 23.976 fps
- Model / tier / fit: `2x_AnimeJaNai_fp16` / `lossless-hevc` / `fit`

The connection and library UI follows the device orientation. Selecting a
file requests sensor landscape and waits for the recreated Compose surface
before `open_session`, so the server receives the real 2960×1848 target.
Android 16 ignores orientation requests for API-36 large-screen apps by
default; the Activity therefore uses the documented restricted-resizability
compatibility property while the player view is active. Leaving playback or
entering failure restores unspecified orientation.

## Decode and output path

- `hwdec-current`: `mediacodec`
- Codec: `H.265 / HEVC (High Efficiency Video Coding)`
- MediaCodec component: `c2.qti.hevc.decoder`
- mpv output: `gpu`, Android OpenGL ES context, MediaCodec decode directly to
  the Android Surface
- mpv runtime: `mpv v0.41.0-556-g9ce79bcaa`
- FFmpeg runtime: `N-124096-gfc4960b155`
- Both versions are emitted in the structured device report as `mpv_version`
  and `ffmpeg_version`.

This is the required hardware path. FFV1 is intentionally unsupported on
Android because it is software-decode-only and would impose unacceptable
battery and thermal costs; it remains available to desktop clients.

## Endurance and natural EOS

The same episode was used for the endurance run and replay so the test covered
both steady-state transport and a real end-of-stream boundary.

| Leg | Observed duration | Natural EOS | Output / decoder drops | Cache pause |
|---|---:|---:|---:|---:|
| Complete playthrough A | 23:41 | Yes | 20 / 0 | No |
| Immediate replay sample | 6:43 | Not reached; stopped after aggregate exceeded 30 min | 0 / 0 | No |
| Complete playthrough B | 23:46 | Yes | 75 / 0 | No |

Both complete playthroughs produced mpv's `end-file`/`ENDED` state. An Android
ImageReader timeout warning followed after the final frame, but the process,
UI, and control connection remained responsive. Back then completed teardown
and left zero server sessions. The server session intentionally stays open at
EOS until the client closes it.

The required 30-minute interval was playthrough A plus the immediate replay
sample: approximately 43,700 presented frames and 20 output drops, or about
0.046%, below the 0.1% hard gate. The extra complete replay requested for a
second EOS boundary recorded two isolated mpv output-scheduling bursts (75
total, 0 decoder drops). The screen remained on at a fixed 120 Hz, the Surface
was not replaced, server/buffer health stayed normal, and the count was stable
for the final ten minutes. These measurements predate the server-side Cover
crop. They showed no decoder loss or cache rebuffer, but do not by themselves
prove that every missed output deadline was harmless display scheduling.

Steady-state observations across the completed interval:

- Hardware decoder stayed `mediacodec`; decoder drops stayed at zero.
- Average receive throughput was roughly 200–270 Mbps depending on sample.
- The total reported buffer normally cycled around 7–11 seconds as the server
  watermark paused and resumed production; the client never paused for cache.
- The byte-bounded pre-mpv queue returned to zero and cycled under pressure
  rather than growing without bound.
- Representative server throughput was about 31 fps, with decode 8.7 ms,
  inference 31.8 ms, fit 8.3 ms, and encode 1.1 ms in a steady-state sample.

## Memory and thermals

PSS samples during playthrough A were 641, 567, 613, 641, 546, 525, and
454 MiB. The series has no upward trend; the native player/decoder memory is
reclaimed and reused rather than growing with received media. A sample during
playthrough B was 483 MiB, followed by 600 and 565 MiB, within the same bounded
range.

Thermal service stayed at status 0 throughout sampled points. AP temperature
was approximately 24.4–25.9 °C and skin temperature 25.6–27.7 °C. No thermal
throttling or native crash was observed. Battery delta, panel refresh mode,
and Wi-Fi channel/link-rate metadata were not captured; they are not required
for this Phase 1 gate and belong in the longer Phase 6 device matrix.

## Repetition and lifecycle

| Test | Required | Completed | Result |
|---|---:|---:|---|
| Open/play/Back teardown | 20 | 20 | Pass: every cycle reached `PLAYING`, 0/0 drops during the sample, and zero server sessions after Back |
| Live Surface replacement | 10 | 10 | Pass: ten forced rotation replacements retained the same server session and returned to `PLAYING`, 0/0 drops |
| Background/foreground | 1 | 1 | Pass: normal launcher resume retained the same activity/session and returned with 0/0 drops |

The acceptance gate requires ten Surface recreation cycles using rotation,
split-screen resize, or Activity recreation. Ten rotation-based replacements
were run. Split-screen and process-death recovery were not separately claimed;
process death is outside Activity recreation and Phase 1 has no automatic
session resume.

The Phase 1 transient-background policy is to retain the Activity-scoped
session and reattach the Surface on normal foreground return. This is lifecycle
retention, not a user-facing background playback feature: there is no audio,
MediaSession, notification, or Picture-in-Picture yet.

## Failure injection

| Condition | Observed behavior | Bounded time | Result |
|---|---|---:|---|
| Unreachable relay host | Visible connect timeout and usable retry | 15 s | Pass |
| Suspended/aborted control socket | Visible `Software caused connection abort` failure | Immediate on resume | Pass |
| Stop relay server during playback | Visible `Playback failed — Connection reset`; process remained alive; Retry returned to library after restart | About 12 s observation window | Pass |
| Disable tablet Wi-Fi during playback | Visible `Software caused connection abort`; Wi-Fi restore plus Retry returned to library | About 15 s observation window | Pass |
| Truncated media payload | On-device instrumentation passed a header plus half-payload into production `MediaFraming.read`; it raised `EOFException` without hang/crash | Immediate | Pass |
| Background/foreground | Same server session ID before Home, while backgrounded, and after normal launcher resume | 12 s round trip | Pass |

Server and Wi-Fi loss both left the application process responsive and did not
leak a server session. Phase 1 intentionally requires manual retry rather than
automatic reconnect.

## Physical-device defects found and fixed

1. Controller collectors used non-atomic read/copy/write operations, allowing
   stale `FAILED` state to hide a successful retry. Collector cancellation is
   now awaited and state updates are atomic.
2. `GET /library` consumed its response body on the Android main thread. The
   full response lifetime and controller teardown now stay on `Dispatchers.IO`.
3. Android selected IPv6 for a generic loopback listener while mpv was given
   `127.0.0.1`. The per-load listener now binds explicitly to IPv4.
4. Opening from portrait negotiated the portrait bounds before the Activity
   rotated. Playback now waits for a landscape surface before `open_session`.
5. Android 16 ignored orientation requests on the large-screen target until
   the Activity opted into the platform compatibility property. The property
   is now scoped to the Phase 1 Activity and orientation is restored on exit.
6. Lossless-HEVC Cover encoded and decoded off-screen side columns, producing
   a repeatable high-bitrate startup segment and visible catch-up. Cover is now
   center-cropped server-side before resize/encode; the rejected
   `framedrop=no` workaround has been removed.

## Final Phase 1 decision

- Acceptance gate: **passed**
- Proven: protocol-v1 server-library session with no uplink, lossless-HEVC
  delivery at 2960×1664, Qualcomm hardware decode, bounded live buffer
  reporting, two natural EOS boundaries, repeated clean teardown, live Surface recovery,
  normal foreground recovery, and bounded malformed/network/server failures
- The Cover output-drop follow-up now has a server-side bandwidth/decode-work
  fix and awaits an exact-file device rerun. Decoder drops and cache pauses
  remain separately observable.
- Phase 2 follow-up: obtain a PGS/VobSub sample and continue reducing distant
  seek/reload latency; the Phase 2 device results are recorded above
