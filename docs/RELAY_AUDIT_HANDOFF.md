# Relay audit handoff for the Android client

Recorded 2026-09-22 for the next Android codebase audit. This is a documentation
handoff, not an implementation or Android-device validation record.

The subsequent Android audit and implementation results are tracked in
[ANDROID_AUDIT_2026-09-22.md](ANDROID_AUDIT_2026-09-22.md). Statements below about
pending Android work describe the handoff baseline, not the completed fixes.

- Original Android inspection: `562854b` on `codex/muxed-aux-tracks` (v0.17.0 application
  code plus the existing muxed-auxiliary migration plan).
- Relay/reference client: `dcbb20d`, branch `fix/codebase-audit-2026-09-22`,
  [relay PR #30](https://github.com/brpjerry/upscale-relay/pull/30).
- Tested Windows GUI ZIP: application revision `0330667d`; the subsequent
  relay commit only records acceptance results. Do not assume an arbitrary
  installed server or the default branch includes these changes.
- Authoritative sources: [protocol contract][protocol], [fix and validation
  report][audit], and the [existing Android migration plan][migration].

Reconciled after pulling Android `0624bf2` (v0.18.0): muxed-track negotiation,
effective auxiliary modes, nullable external-media endpoints, verified cached
fonts, and player track remapping are now implemented. Initial Galaxy Tab S9
Ultra results and remaining acceptance gates are recorded in
[ANDROID_DEVICE_NOTES.md][device]. The original inspection table below remains
a historical baseline. Source-presence metadata, `seek_progress` handling and
acknowledged teardown remain follow-ups in the pulled code; this reconciliation
does not constitute a new Android audit or device validation.

## Protocol changes and server behavior

Protocol remains **v1**. The 41-byte media handshake, 25-byte little-endian
packet header, signed PTS/DTS, source time base and epoch meanings are unchanged.
Unknown additive JSON fields/messages remain safe to ignore. No new quality-tier
IDs or codec support were introduced by this audit.

| Area | Available relay behavior | Android action |
| --- | --- | --- |
| Payload size | Python senders/receivers now enforce **64 MiB per framed payload**, checking the header before reading/allocating its body. Oversized mux flushes are split without losing bytes. | Android already enforces the same limit in `MediaFraming`. Retain golden tests; add boundary and reject-before-body tests. File size is unrestricted by this per-packet limit; downlink chunks are opaque Matroska bytes, not necessarily one frame. |
| Source metadata | `session_opened` for `server_file` adds optional `source_has_audio` and `source_has_auxiliary` booleans. Auxiliary means audio **or** subtitles. | Still pending in v0.18.0: add nullable `Boolean?` values to `SessionInfo`; absent/null is unknown, never equivalent to `false`. Use local source metadata for uplink when available. |
| Seek progress | Optional `seek_progress.stage` is `subtitle_index` or `video_decode`; `message` and `subtitle_indexed_s` can be null. Existing epoch, target, keyframe, discarded-frame and elapsed-time fields remain. | Android currently ignores this message. Route it to the active seek UI/watchdog, reject stale epochs and tolerate unknown stages. Indexed seconds describe source coverage, not playback position or a completion percentage. |
| Teardown | `closed` is the successful native-resource-release acknowledgement. `state:"closed"` is only a state notification. Native cleanup failure closes control with code 1011, sets `/status.restart_required`, and rejects later opens with `server_restart_required`. | Await the acknowledgement with bounded failure handling before replacing a session. A delay, socket EOF, or empty session registry alone does not prove cleanup succeeded. This contract already existed; the audit strengthened ownership and failure handling. |
| Media ownership | Only one attached socket per session/direction is accepted. A duplicate gets handshake rejection `0x01` and EOF. Teardown closes owned media sockets and releases queued data. | Retain the persistent downlink across seeks; test duplicate rejection and cleanup rather than reconnecting media for each epoch. |

Auxiliary-source selection must follow the **confirmed** session, not the
requested mode. This refines the older migration plan's blanket external-URL
fallback rules:

| Confirmation / source metadata | Relay player input |
| --- | --- |
| `aux_tracks:"muxed"` | Relay epoch only; never attach `/media`. |
| External, `source_has_auxiliary:false` | Relay epoch only; the original has no audio/subtitle tracks. |
| External, auxiliary not confirmed absent, `source_has_audio:false` | One delayed `sub-add`; do not wait for an audio clock that cannot exist. |
| External, audio true or unknown | Preserve the single delayed `audio-add` path; it contributes the source's subtitles too. |
| Old server with absent metadata/modes | Preserve existing external behavior. Do not infer video-only from missing fields. |

Apply the same source-presence distinction to SAF/uplink when local metadata is
known; otherwise preserve its bridge and existing compatibility behavior.
Do not attach both `audio-add` and `sub-add` for the same original.

### Subtitle preservation and seek progress

For confirmed muxed sessions, the server now preserves overlapping ASS/SSA,
SubRip, WebVTT and plain-text subtitle packets in a **temporary per-session
SQLite index**. It retains original payloads, timestamps and durations. The
index also preserves codec side data. It is removed at teardown, capped at
256 MiB on disk with a 2 MiB page cache;
exhaustion is an explicit session error. This is server storage, separate from
the client's persistent font-object cache.

Ordinary demux fills the index progressively. The first forward seek into an
unindexed region scans the missing source prefix without decoding video;
backward/repeated seeks reuse it. Indexing adds no unconditional full-source
scan at startup, but a first far seek can take longer. Indexing progress can keep
`seek_progress` notifications alive
beyond 60 seconds while coverage advances. Quick seeks can send no progress
messages. `seek_ready` still only acknowledges the flush, not available media.

Review all Android first-media/seek/watchdog deadlines together: continuing
index progress must not be mistaken for a dead server, while a non-advancing or
disconnected server must still fail within a bounded interval. The existing
`session_progress` open keepalive is a separate path.

Stateful bitmap formats such as PGS/VobSub currently force confirmed
`aux_tracks:"external"`. Do not force muxed mode to test them. Audio preroll and
subtitle events may begin before the first post-seek video PTS; never trim all
auxiliary data to the requested target.

## Improvements available to adopt or preserve

Muxed auxiliary tracks and authenticated cached fonts were already available
before this audit; Android adoption landed in `0624bf2`, following
[MUXED_AUX_TRACKS_PLAN.md][migration].
The audit adds subtitle recovery and more robust cancellation/ownership around
that path. Negotiate `muxed_aux_tracks` and `attachment_cache`, then honor the
effective `aux_tracks` / `aux_attachments` response. Missing values retain
`external` / `embedded` behavior.

For confirmed cached mode, require the manifest/token and prepare the verified
font view **before** `loadfile`. Fetch each missing SHA-256 object through
`GET /attachments/<hash>` with the bearer token; enforce 64 MiB/object and
256 MiB/manifest, verify size/hash, publish atomically and bound the persistent
store. The desktop uses 512 MiB; Android's storage budget still needs device
validation. Seeks reuse the view. Protect active objects/views from eviction,
cancel and join downloads, and reclaim results finishing after cancellation.
Never log tokens or substitute `/media` after a cached muxed open fails.

Each epoch has fresh tracks: re-enumerate and remap explicit audio/subtitle
choices, including subtitles-off. Preserve pause intent through initial load,
seek, reconnect and settings restart. Keep absolute PTS and the established
stop/retire/150 ms/load sequence; never pass `start=` to a relay stream.

**Keep Android's measured audio-ready policy.** The desktop releases its external
audio hold when the attach command returns because paused `audio-pts` is not
reliable there. Android's [hard rules](../CLAUDE.md) instead require its bounded
audio-ready wait based on physical-device evidence. Do not copy the desktop
release condition mechanically. v0.18.0 already releases the muxed hold on the
first playback restart. Add explicit external video-only and subtitle-only
load states that need no audio wait, then verify each on Android.

The desktop also fixed keyboard pause versus toolbar intent, local fallback
readiness/controls, stale player callbacks and seek-after-network-EOS. Android
already has protections in several of these areas; use the failure scenarios
below as regression targets rather than assuming identical bugs.

For UX, the desktop now groups streaming options in Playback Settings and keeps
connection/transport controls visible, with volume/mute controls and coherent
fullscreen colors. Android v0.17.0 already has a dedicated model sheet and
playback controls. Audit its compact/tablet layouts, slow-seek explanation,
opening/failure states, accessibility, light/dark contrast, insets and touch
targets on their own merits; do not transplant a desktop dock.

## Original Android audit entry points (`562854b`)

These are static observations at the original Android revision, not a completed
Android audit or newly reproduced defects. The reconciliation note above
identifies implementation work superseding this baseline. Paths below are relative to the
repository root, under `src/main/kotlin/org/upscalerelay/<package>/`.

| Module / file | Observation and follow-up |
| --- | --- |
| `relay-protocol`, `protocol/MediaFraming.kt` and `protocol/Messages.kt` | Payload validation is already present. Muxed/cache capabilities, effective modes, manifest/token and source-presence booleans are not parsed yet. Extend old/new golden fixtures and nullable defaults. |
| `relay-client`, `client/ControlChannel.kt` | Open keepalives and seek epoch predicates exist. `seek_progress` is ignored. `teardown()` sends, delays 100 ms and closes without awaiting `closed`; prioritize a real teardown barrier. Pending replies are keyed by message type, so preserve the serialized/coalesced seek contract or explicitly support multiple epoch waiters. |
| `relay-client`, `client/RelaySessionController.kt` | `PlaybackEndpoint.originalMediaUrl` is mandatory and server-file preparation always builds `/media`. Teardown wraps the control call in `runCatching`; audit how unconfirmed cleanup reaches recovery and the UI. |
| `relay-client`, `client/MediaTransport.kt` | `DownlinkReceiver` already continues after packet EOS; only the per-epoch loopback sender finishes. Preserve this distinction and test a seek after every byte of the old epoch has arrived. Audit close during connect/accept/blocked write and after the worker join timeout. |
| `relay-client`, `client/BoundedMediaQueue.kt` | Existing 256 MiB byte backpressure is valuable. Audit total storage including socket/mpv queues and item overhead; byte limits alone do not limit the count of zero-payload items. Never discard arbitrary Matroska chunks to make room. |
| `relay-client`, `client/UplinkSender.kt`; `relay-demux` source/bridge owners | Uplink batches currently bound packet count (16), not aggregate bytes. Audit copied batch storage as well as generation isolation, ordered epochs and fresh extractors. Test cancellation during blocking reads/opens, superseded workers, partial startup and immediate Play original controls. |
| `player-mpv`, `player/mpv/MpvPlayerEngine.kt` | Audit load-generation guards, single external attachment, track remapping, pause/stop intent, native cleanup, Surface replacement and direct-local readiness. Android MediaCodec behavior needs its own evidence; desktop `vaapi-copy` is not an Android setting. |
| `app`, `android/RelayViewModel.kt`; `relay-client`, `client/FailureTaxonomy.kt` | Normal seeks already cancel/join their predecessor. Cover saved-position open, reconnect, settings restart and auto-advance too. Disposal has a five-second budget; reconcile it with confirmed teardown and surface `server_restart_required` without a futile retry loop. |

## Regression and device checks to carry into the audit

1. **Framing/epochs:** exact fractional source versus returned-container video
   PTS, maximum/oversized/truncated lengths, arbitrary mux fragmentation, one
   discontinuity per epoch, and final EOS. Compare container timestamps, not
   outer chunk PTS. Account for the server's optional keyframe-fast seek mode.
2. **Fast-server EOS:** send a complete short clip before playback ends, pause,
   then seek backward/forward and issue overlapping seeks. Stale payloads,
   discontinuities and EOS must never reach the replacement epoch.
3. **Auxiliary matrix:** video-only, subtitle-only, audio+text subtitles,
   old-server defaults, muxed/cached/embedded confirmations and bitmap external
   fallback. Seek inside a long subtitle that started before the preceding video
   keyframe; test first unseen far seek, repeat/backward seeks and cancellation
   while indexing. Count tracks and `/media` requests.
4. **Resource ownership:** slow consumer, repeated open/Stop, failure after server
   allocation, concurrent cancellation, blocked sockets and font downloads,
   duplicate attachments, missing `closed` and server restart requirement.
   After acknowledged teardown, check both sessions and health; remote `/status`
   alone cannot prove subtitle-spool or GPU-process deletion.
5. **Device/UI:** notification/keyboard/touch pause intent, Play original,
   settings changes, track choices, PiP, screen-off, Surface recreation and
   compact/tablet/fullscreen layouts. Reuse [device gates][device] for long A/V
   runs, MediaCodec, battery/thermal behavior and remaining S Pen/DeX/bitmap tests.

Use existing JVM/Android tests and verification commands in [DEVELOPMENT.md](../DEVELOPMENT.md)
and [README.md](../README.md). Create focused, durable regressions before porting
fixes. No Gradle build, APK install or Android-device test was run for this note.

The relay audit's local suite passed **282 tests, with 4 documented skips**;
Linux GUI/CPU-inference and Windows build/runtime CI also passed. The installed
Windows ZIP passed exact timestamps across three complete epochs, muxed FLAC/ASS,
cached fonts and confirmed teardown. Real TensorRT/NVENC 1080p-to-4K lossless
playback passed two minutes headless and a separate minute rendered on Wayland,
with no sampled buffering or additional steady-render drops. These are useful
server/reference-client baselines, not Android acceptance.

Reproduce playback problems on `passthrough` and a sustainable network tier
first. For model measurements, confirm the server GPU is idle. Read cache trend,
buffering and watermark state alongside `/status` FPS: pacing pauses can lower
the sampled FPS without implying insufficient capacity. The intermittent native
4K GPU crash remains unresolved; bounded successful runs did not reproduce it.

[protocol]: https://github.com/brpjerry/upscale-relay/blob/dcbb20d2c5df8698ba59719844b636ad7a75a187/docs/PROTOCOL.md
[audit]: https://github.com/brpjerry/upscale-relay/blob/dcbb20d2c5df8698ba59719844b636ad7a75a187/docs/AUDIT_FIXES.md
[migration]: MUXED_AUX_TRACKS_PLAN.md
[device]: ANDROID_DEVICE_NOTES.md
