# Android muxed auxiliary tracks and attachment cache plan

Status: **planned; server and Linux reference implementation are complete and
live-verified**

The relay server can now stream-copy every original audio/subtitle track into
each per-seek Matroska epoch. It can also omit immutable subtitle-font bodies
from those epochs and expose them once through a bounded, authenticated,
content-addressed cache protocol. The Android client still uses the legacy
`GET /media/<path>` external-media path for every server-library session.

This plan migrates Android server-library playback to the negotiated muxed
path without changing local/SAF playback, weakening epoch semantics, or
removing compatibility with older/unsupported servers.

The authoritative wire contract is the sibling server repository's
`docs/PROTOCOL.md`. No protocol-version bump is required: every field is
additive, requests are opt-in, and the server's confirmed mode is
authoritative.

## Why this migration matters

The current Android server-file path combines:

```text
relay Matroska video over framed TCP
    + full original file over HTTP /media for external audio/subtitles
```

On a measured 27.54 GiB remux, source video was 97.4% of `/media` bytes. The
tablet therefore downloaded almost the entire original file merely to consume
its audio and subtitle packets. External media also requires the proven
post-`PLAYBACK_RESTART` `audio-add` lifecycle, an HTTP range seek after every
epoch reload, an audio-ready pause hold, and a separate drift/failure path.

The negotiated path becomes:

```text
server video -> upscale/encode ----+
original audio/subtitle stream-copy +-> one epoch Matroska -> existing downlink
cached subtitle fonts -------------+   (font bodies omitted after cache setup)
```

The Linux reference client was verified on the real rendered UI with muxed
audio/subtitles, cached fonts, active and paused seeks, and safe hardware
decode. A regression seek on the representative 23-minute file improved from
7.88 s to 0.86 s after the server's auxiliary demuxer began seeking on video
keyframe cues. Server first output was 273 ms and post-seek A/V error remained
below about 1.2 ms.

## Non-negotiable compatibility matrix

| Source / server confirmation | Android behavior |
|---|---|
| Local SAF/uplink | Keep the local HTTP bridge and external attach lifecycle |
| Server file, capability absent | Request no new fields; keep `/media` |
| Server file, request rejected/falls back to `external` | Keep `/media` |
| Server file, confirmed `muxed` + `embedded` | Load relay epoch alone; fonts remain in its Matroska header |
| Server file, confirmed `muxed` + `cached` | Prepare verified font view, then load relay epoch alone |

Rules:

- Never omit `/media` because muxed mode was merely requested. Only
  `session_opened.aux_tracks == "muxed"` authorizes that.
- Never attach `/media` to a confirmed muxed epoch. It duplicates tracks,
  source reads, and seek lifecycles.
- Never request muxed tracks for an uplink session. The server does not own the
  local file's auxiliary tracks.
- Never assume cached mode was accepted. `embedded` remains valid even when
  `cached` was requested, including files with no attachments.
- Never bump protocol v1 for these additive fields. Missing capability/session
  fields mean `false`/`0` and `external`/`embedded`.

## Current Android implementation points

- `relay-protocol/.../Messages.kt`: `Capabilities` does not parse
  `muxed_aux_tracks` or `attachment_cache`; `SessionInfo` does not parse the
  effective auxiliary mode, manifest, or token.
- `relay-client/.../ControlChannel.kt`: server-file `open_session` sends no
  auxiliary request fields and always exposes `mediaUrl(path)`.
- `relay-client/.../RelaySessionController.kt`: `preparePlayback()` always
  constructs `/media`; `PlaybackEndpoint.originalMediaUrl` is non-null and is
  copied into every seek/reconnect endpoint.
- `player-mpv/.../MpvPlayerEngine.kt`: relay `load()` requires an HTTP
  original-media URL, holds the epoch paused, and dispatches one `audio-add`
  plus an `audio-pts` readiness wait after `PLAYBACK_RESTART`.
- `app/.../RelayViewModel.kt`: initial open, seek, resume, reconnect, and
  settings-restart paths all call that two-URL player API.

The existing external lifecycle is correct and load-bearing. It is not deleted;
it becomes one branch selected by the confirmed mode.

## Slice 1 — protocol models and embedded muxed proof

Land negotiation and muxed playback with `aux_attachments:"embedded"` first.
This proves Android/libmpv multi-track live Matroska behavior independently of
font-directory behavior.

### Protocol models

Extend `relay-protocol/src/main/.../Messages.kt`:

- `Capabilities.muxedAuxTracks: Boolean = false` from
  `capabilities.muxed_aux_tracks`.
- `Capabilities.attachmentCacheVersion: Int = 0` from
  `capabilities.attachment_cache`.
- `SessionInfo.source`, defaulting compatibly when absent if useful to
  diagnostics.
- `SessionInfo.auxTracks`, default `"external"`.
- `SessionInfo.auxAttachments`, default `"embedded"`.
- `SessionInfo.attachmentManifest: List<AttachmentManifestEntry>`.
- `SessionInfo.attachmentToken: String?`.
- `AttachmentManifestEntry(name, mimeType, size, sha256)` with strict bounds
  and lowercase SHA-256 validation before any file/network operation.

Do not model request and response strings as an enum that throws on a future
unknown value during JSON parsing. Parse tolerantly, then treat every value
other than the explicitly supported confirmation as the safe external or
embedded fallback.

### Control request

Change only server-file `ControlChannel.openSession()`:

- send `aux_tracks:"muxed"` when `Capabilities.muxedAuxTracks` is true;
- in Slice 1 send `aux_attachments:"embedded"` explicitly;
- omit both fields against an old server;
- leave `openUplinkSession()` unchanged.

Pass the capability decision from `RelaySessionController` instead of making
`ControlChannel` retain a second copy of negotiated state.

### Controller endpoint

Make `PlaybackEndpoint.originalMediaUrl` nullable and add the effective
auxiliary/attachment modes. For a server-file session:

- confirmed muxed -> `originalMediaUrl = null`;
- confirmed external or absent -> build the existing `/media` URL;
- local/uplink -> retain the supplied local bridge URL unconditionally.

The same nullable value must survive `seek()`, reconnect/resume, watch-position
resume, auto-advance, and mid-play settings restarts. Do not reconstruct
`/media` during a seek from the original request; copy the effective mode from
the opened session.

### Player load state

Replace the two-string relay API with an explicit request, for example:

```kotlin
data class RelayLoad(
    val streamUrl: String,
    val externalMediaUrl: String?,
    val subtitleFontsDirectory: String?,
    val auxMode: AuxMode,
)
```

`MpvPlayerEngine` must distinguish a muxed load from a direct-local load and
from an external relay load. A nullable pending URL alone is insufficient
because `null` can mean either "muxed and waiting for playback restart" or
"nothing pending/retired."

Preserve the 150 ms stop/retire/reload sequence and never pass `start=` to a
relay epoch. Continue loading relay epochs with a pause hold:

- external confirmation: first `PLAYBACK_RESTART` dispatches the existing one
  `audio-add`, waits for valid `audio-pts`, then restores caller pause intent;
- muxed confirmation: first `PLAYBACK_RESTART` performs no external command,
  reapplies explicit track choices after the fresh Matroska track list exists,
  and immediately restores caller pause intent;
- direct local playback: unchanged and may use its normal seekable-file start.

Track IDs are not a protocol identity. When the user selects a track, retain a
descriptor (type, language, title, codec when exposed, and same-descriptor
occurrence/source order) beside its current mpv ID. Re-enumerate after every
muxed reload and remap that descriptor before reapplying the choice; reuse a
numeric ID only when the new track at that ID still matches. Preserve
subtitles-off as an explicit choice. The server currently builds deterministic
stream order, but client correctness must not depend on an undocumented
numeric-ID guarantee.

### Slice 1 gate

On the Galaxy Tab S9 Ultra with `passthrough` first:

1. Confirm `session_opened.aux_tracks == "muxed"` in redacted diagnostics.
2. Confirm `/media` receives zero requests for that session.
3. Confirm every audio/subtitle track appears exactly once and reports
   `external=false` in mpv.
4. Exercise active and paused far seeks; the old epoch disappears immediately,
   caller pause intent survives, and no `audio-add` appears in verbose mpv logs.
5. Retain non-default audio, a selected subtitle, and subtitles-off across
   several epochs.
6. Verify A/V drift, decoder/output drops, cache behavior, PiP, and background
   playback against existing device thresholds.

Use a file whose embedded attachment total stays below the server's legacy
4 MiB live-header limit for this slice; a larger embedded request correctly
falls back to external. Do not enable cached attachments until that small-font
ASS sample renders with the correct fonts in muxed mode.

## Slice 2 — verified Android attachment cache

After Slice 1 passes, request `aux_attachments:"cached"` when
`attachment_cache >= 1` and implement the cache before loading the epoch.

### Ownership and storage

Keep the download/cache implementation in the pure-JVM `relay-client` module
(for host tests), parameterized by a `java.nio.file.Path` supplied by the app.
The ViewModel can construct it beneath an app-private cache root such as:

```text
cacheDir/relay-attachments/
  objects/<lowercase-sha256>
  sessions/<sanitized-session-id>/<sanitized-font-name>
```

Object files persist across sessions until bounded eviction. Session views are
temporary hard links when supported and verified copies otherwise; they retain
font-like filenames for libass. Remove a retired session view, but never delete
an object currently referenced by the active view.

Match the proven desktop safety rules:

- 64 MiB maximum per attachment;
- 256 MiB maximum declared manifest total;
- bounded object store (start at 512 MiB, revisit only with device storage
  measurements);
- basename-only sanitized names, bounded to 128 characters;
- duplicate hashes download once;
- duplicate filenames gain a deterministic hash suffix;
- stream response bodies to a same-directory temporary file;
- reject overlong bodies before publication;
- verify exact declared size and SHA-256;
- `fsync`/close and atomically rename only after verification;
- delete interrupted or mismatched temporary files;
- LRU-like eviction by last verified use, excluding active hashes.

An Android cache directory is disposable. OS eviction simply creates a safe
cache miss and redownload; it must not create a half-valid entry.

### Authenticated fetch

Add a bounded `ControlChannel`/cache fetch using the existing OkHttp client:

```text
GET /attachments/<sha256>
Authorization: Bearer <attachment_token>
```

Never place the token in a URL, DataStore, telemetry snapshot, exception text,
or ordinary log. The token expires with teardown and authorizes only hashes in
that session's manifest. HTTP cache headers are an optimization; the verified
content hash is authority.

For a confirmed cached session, require both a valid manifest and token.
Materialize the complete view before downlink/player load. A download or hash
failure must fail the open and tear down cleanly; never continue while claiming
font parity, and never silently add `/media` to a session already confirmed as
muxed. Recovery may reopen a new session and retry normally.

### libass registration

Pass the materialized view path through `PlaybackEndpoint` and set mpv's
`sub-fonts-dir` before `loadfile`. Reset it to the normal/default value for
external/local or direct-local loads so one file's view cannot leak into
another playback mode.

The proof gate is visual and diagnostic, not merely "subtitle text appeared":

- use an ASS/SSA file whose intended attached typeface is visibly distinct;
- clear the Android cache, open once, and record downloaded bytes/hashes;
- seek repeatedly and require zero additional attachment requests;
- tear down/reopen the same file and require cache hits with zero body bytes;
- open a second file sharing font hashes and require object reuse;
- compare a screenshot or libass verbose font-selection log with embedded mode.

Only after that gate should cached mode become the default request on capable
servers.

## Slice 3 — orchestration, diagnostics, and cleanup

Update every `RelayViewModel` player-load call site:

- initial server-file open;
- initial local-file relay open;
- ordinary epoch seek;
- saved watch-position seek before first load;
- automatic reconnect/resume;
- mid-play model/quality/framing/filter restart;
- auto-advance;
- direct local fallback.

Server-file muxed endpoints pass no external URL. Local/uplink endpoints keep
the bridge. Direct fallback remains a direct bridge load and is not a relay
auxiliary mode.

Add redacted diagnostics for:

- advertised muxed/cache capabilities;
- requested and confirmed auxiliary/attachment modes;
- manifest object count and declared bytes (never names/paths/tokens);
- cache hits, misses, verified bytes, and evictions;
- whether mpv tracks are embedded or external;
- count of `/media` requests in debug/integration instrumentation;
- time from seek commit to first muxed position/audio PTS.

Update watchdog assumptions. The external-audio readiness timeout is not part
of muxed loads. Drift, surface-loss, PiP, reconnect, and buffer watchdogs remain
active because they protect playback independently of track transport.

On teardown, cancel and join any attachment download before deleting its
session view or closing the control session. A cancelled OkHttp/file write must
not outlive the token/session or publish a partial object.

## Automated coverage

### `relay-protocol`

- New and old `capabilities` JSON parse to correct defaults.
- New and old `session_opened` JSON parse to authoritative modes.
- Valid manifests parse; invalid hash, negative/oversized size, oversized
  total, unsafe name, missing token, and unknown mode take the safe path.
- Python-generated capability/session fixtures remain Kotlin/Python compatible.

### `relay-client`

- `open_session` includes muxed/embedded or muxed/cached only when allowed.
- Uplink open never includes muxed auxiliary fields.
- Requested muxed + confirmed external produces an endpoint with `/media`.
- Confirmed muxed produces a null external URL through initial load and seek.
- Cache miss downloads once; cache hit performs no body request.
- Hash/size mismatch and interrupted response never publish an object.
- Duplicate hash/name behavior, atomic replacement, bounded eviction, and
  active-object protection.
- Teardown cancels downloads and removes only the session view.

Use OkHttp `MockWebServer` or an equivalent bounded fake; do not require a
local relay server for these tests.

### `player-mpv`

- Relay load options still contain `network-timeout=0`, `pause=yes`, and no
  `start=`, `audio-file`, or `sub-files` option.
- Confirmed muxed loads never dispatch `audio-add`.
- External loads dispatch exactly one `audio-add` after the first
  `PLAYBACK_RESTART` and retain the existing audio-ready hold.
- Muxed loads release their hold on the first restart and preserve caller
  pause intent.
- Track choices/off survive a synthetic reload; retired generations cannot
  apply late selection or release pause.
- Font directory is applied before cached-mode `loadfile` and reset for other
  modes.

### Existing regression suites

All framing, bounded queue, stale epoch, seek coalescing, local extractor,
direct fallback, reconnect, player URL redaction, and state-machine tests must
remain green. The sibling Python suite remains the server-side authority for
auxiliary PTS equivalence, mux fallback, attachment authorization, and seek
cue behavior.

## Physical-device acceptance matrix

Use `passthrough` first and a network/tier the tablet can sustain. Record
server `/status` alongside Android telemetry so GPU or Wi-Fi starvation is not
misclassified as a client regression.

Required on the Tab S9 Ultra:

1. Embedded muxed smoke: initial play, active seek, paused seek, and natural
   EOS with all tracks and no `/media` request.
2. Cached-font cold/warm runs with byte counts proving one verified transfer
   and no transfer on seeks/reopen/shared hashes.
3. Thirty-minute A/V run within the existing ±50 ms drift and drop gates.
4. Twenty-five-action seek storm converging on the final epoch/target with no
   stale frame, duplicate track, external attach, or deadlock.
5. Non-default audio/subtitle and subtitles-off retained across seeks,
   reconnect/resume, and mid-play setting restarts.
6. SSA font correctness; PGS/VobSub remains a separate required sample because
   bitmap subtitle seek behavior is stateful and not proven by ASS.
7. Pause longer than 90 seconds, background/screen-off, Surface replacement,
   PiP enter/exit, and return to full playback without drift or session leak.
8. Cache failure injection (disconnect, truncated body, bad hash, full cache)
   produces bounded recovery/failure and no partial file.
9. Compatibility run with external confirmation proves the existing
   post-restart `audio-add` path still works.
10. Local SAF relay, rapid local seeks, and **Play original** prove the local
    bridge/external path was not changed.
11. Twenty open/play/teardown cycles leave zero server sessions and no active
    attachment view/download thread.

Repeat a representative smoke on the Galaxy S24 Ultra compact layout. After
passthrough is understood, repeat one real model while requiring server
pipeline FPS at or above source FPS.

## Documentation updates when implemented

- Rewrite `README.md` architecture so server-library muxed tracks are the
  normal path and `/media` is compatibility-only.
- Update `DEVELOPMENT.md` buffering/reload notes with the two confirmed player
  branches and cache inspection commands.
- Update `CLAUDE.md` hard rules: retain all external attach rules, scoped to
  local/external confirmation, and add "never audio-add a confirmed muxed
  epoch" plus cache/token rules.
- Update `docs/ANDROID_CLIENT.md` Phase 2/status/parity table and retain the
  external seek-latency history as historical context.
- Append exact APK/server commits, files, mode confirmations, timings, cache
  bytes, A/V/drop metrics, and remaining subtitle gates to
  `docs/ANDROID_DEVICE_NOTES.md`.

## Delivery order

1. Protocol models/defaults and golden tests.
2. Muxed + embedded negotiation through controller/player/ViewModel.
3. Host tests, then physical-device Slice 1 gate.
4. Pure-JVM attachment store and authenticated fetch tests.
5. Android libass font-view integration and Slice 2 cold/warm device gate.
6. Enable cached requests by default on capable servers.
7. Run the full device acceptance matrix and update all Android docs.

Do not combine the first embedded muxed proof with the cache rollout. If
playback or subtitles fail, the split must tell us whether the defect is in
multi-track epoch playback or font materialization.

## Completion criteria

The migration is complete when capable server-library sessions confirm muxed
tracks, Android makes no `/media` request, cached fonts transfer once by hash,
seeks/reconnects/settings restarts preserve selections and pause intent, the
existing device A/V/drop/lifecycle gates pass, compatibility fallback remains
functional, and local/SAF playback is unchanged.
