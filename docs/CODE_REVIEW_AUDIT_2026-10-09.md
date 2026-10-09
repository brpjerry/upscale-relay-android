# Code review audit — 2026-10-09

Reviewed revision: `3ad025b78105badefbd964d7dda2ea13b0b51222` (v0.22.0), fetched from `origin/main` on October 9, 2026. Audit branch: `codex/code-review-audit-2026-10-09`. The working tree was clean before updating `main`; the previous checkout was `seamless-loading`.

This is a findings-only audit. No application, protocol, dependency, or production test code was changed. Source line references below identify the reviewed revision.

## Assessment

The main playback path passed the host checks and a live tablet regression, but the review identified **10 actionable defects: one P1 and nine P2**. Four were reproduced on the tablet or against compiled production code; one additional timeout mismatch was reproduced at component level. The other five are supported by static control-flow evidence and need focused regressions before fixes.

The highest-priority issue is an automatic connection clearing a file-open request made from the cached library. Other defects affect local media framing, logging restoration, server-specific history, browser navigation/sorting, fallback bookkeeping, and player/Settings interaction.

The architecture already has useful boundaries: protocol and transport modules, bounded queues, a centralized mpv wrapper, verified attachment storage, and explicit native teardown acknowledgement. The largest maintenance risk is orchestration and state mapping concentrated in `RelayViewModel.kt` (3,211 lines) and `RelayApp.kt` (2,466 lines), with few tests exercising their asynchronous behavior.

Priority meanings: **P1** should be fixed next because a central workflow can fail; **P2** is a concrete defect that should enter the normal fix queue. Design opportunities later in this document are not counted as defects.

## Review scope

All 30 production Kotlin files across the five modules were reviewed, along with their unit/device tests, manifests, resources, Gradle configuration, CI/release workflows, bootstrap/native scripts, and current development/audit documentation.

| Area | Review emphasis |
| --- | --- |
| `app` | Connection/open/reconnect ownership; preference/history/cache persistence; browser navigation; Compose layout and interactions; Activity/PiP; media service/audio focus; discovery and logging |
| `relay-client` | Control replies/cancellation, session transitions, downlink/uplink ownership, loopback streaming, backpressure, seek deadlines, attachment lifetime and teardown |
| `relay-protocol` | Framing limits, signed timestamps, additive JSON compatibility, auxiliary modes and manifest validation |
| `relay-demux` | SAF access, extractor ownership, NAL conversion, chapter parsing, local HTTP ranges and cancellation |
| `player-mpv` | Native ownership, command ordering, Surface lifetime, muxed/external track handling, pause intent and track remapping |
| Build/release | Pinned bootstrap/native inputs, signing configuration, test/lint coverage and workflow behavior |

The September audit was used to identify regression targets, then checked against current code. Previously fixed issues such as missing `closed` acknowledgement, stale seek replies, zero-length queue pressure, interrupted uplink packet tails, and duplicate external track attachment are not repeated as new findings.

## Actionable findings

### F01 — P1: automatic connection can erase a pending file open

**Evidence: static control flow; not reproduced on the tablet.**

Location: [RelayViewModel.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayViewModel.kt#L1076-L1084), lines 1076–1084. Related: 1028, 1056–1064, 1523–1543, 1583–1591, 2194; [RelayApp.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayApp.kt#L169), lines 169–193.

Start offline with a cached library, select a cached file and press Play (or tap a Recent item) while the automatic connection is waiting in `awaitNetwork()`, then restore connectivity. `openFile()` sets `playingPath` and waits for the background connection. The background call still uses `resetPlayback=true`, so after its wait it clears `playingPath`, `session`, and pause/seek state. The eventual successful open sets the endpoint but does not restore `playingPath`.

The UI chooses the player exclusively from `playingPath != null`. In portrait this can cause the landscape readiness check to time out; in landscape it can leave an allocated/loaded session behind the browser without its player Surface and controls. Quiet wake reconnection has the same reset default.

**Recommendation:** separate connection ownership from playback intent. Browser background connections should preserve a newer open request, using an explicit operation generation or `resetPlayback=false` where appropriate. Add a deterministic regression that pauses connection initialization, starts an open, resumes connection, and asserts the requested path, Surface and endpoint remain associated.

### F02 — P2: valid length-prefixed NAL samples can bypass conversion

**Evidence: reproduced against the compiled production function.**

Location: [AndroidMediaSource.kt](../relay-demux/src/main/kotlin/org/upscalerelay/demux/AndroidMediaSource.kt#L224-L226), lines 224–226 and 255–258.

`normalizeNalUnits()` treats a leading `00 00 01` as proof of Annex B framing. A valid four-byte length field for a first NAL of 256–511 bytes has exactly that prefix. The function returns the entire sample unchanged, leaving later NAL length fields unconverted.

A sample containing a 300-byte first NAL and a two-byte second NAL produced:

```text
unchanged=true
first prefix=[0, 0, 1, 44]
second prefix=[0, 0, 0, 2]
```

Both boundaries should instead contain `00 00 00 01` after conversion. The framing error is verified; actual decoder rejection/corruption and its frequency on real extractor outputs were not measured.

**Recommendation:** retain explicit framing information when available, or validate the complete length-prefixed sample before treating the prefix alone as authoritative. Cover lengths 255, 256, 300, 511 and 512, multiple NALs, malformed lengths, and genuine three/four-byte Annex B input.

### F03 — P2: loopback accept timeout starts before the player can connect

**Evidence: component timeout reproduced; slow end-to-end preparation not tested.**

Location: [RelaySessionController.kt](../relay-client/src/main/kotlin/org/upscalerelay/client/RelaySessionController.kt#L419-L436), lines 419–436 and 312–331; [MediaTransport.kt](../relay-client/src/main/kotlin/org/upscalerelay/client/MediaTransport.kt#L150-L163), lines 150–163.

The loopback listener begins its 30-second `accept()` deadline before preparation returns its URL to mpv. A seek can still be waiting for its acknowledgement under a 60-second control deadline. Initial local preparation also performs downlink and uplink readiness checks sequentially, each permitting 30 seconds. A valid slow preparation can therefore fail with `Accept timed out` before the player gets an opportunity to connect.

A standalone JVM probe observed `SocketTimeoutException: Accept timed out` after 30,015 ms without a player connecting to the standalone listener. Source inspection establishes that controller preparation starts this timer prematurely; the probe did not run the composed controller. A subsequent connection assertion encountered a sandbox socket restriction and is not counted as verified.

**Recommendation:** bind early if necessary, but arm the player-connect timeout only when the endpoint is usable. Keep preparation and player attachment independently bounded. Test the composed controller path, not just the listener's timeout.

### F04 — P2: diagnostic logging preference is not restored

**Evidence: reproduced on the tablet after force-stop/relaunch.**

Location: [RelayViewModel.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayViewModel.kt#L493-L496), lines 493–496; preference restoration at 355–390.

The preferences collector restores many UI fields but omits `fileLoggingEnabled`, then calls `syncFileLogging(savedValue)`. That function runs only when the requested value equals the current UI flag, whose initial value is false. A saved true value is rejected. Importing a differing logging preference has the corresponding problem.

On the tablet, enabling the switch created a named Documents log. After force-stop/relaunch, the switch was off and the active-log label disappeared. [Screenshot after restart](audit-2026-10-09/logging-after-restart.png).

**Recommendation:** restore the desired preference before synchronizing the logger, or model desired logging and actual logger status separately. Test launch with logging enabled plus imports that both enable and disable it.

### F05 — P2: server history and recents are not scoped to the server

**Evidence: static data-flow inspection.**

Location: [RelayViewModel.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayViewModel.kt#L198-L201), lines 198–201; related 1574, 1595 and 2423–2433.

Progress keys use only `server:<relative path>`; recents also store only paths. If servers A and B contain different media at the same relative path, B inherits A's resume position and watched flag. Autoplay can skip an unwatched B file, and opening a recent item uses whichever server is currently configured. The library cache already records its origin, so history behaves inconsistently with browsing.

**Recommendation:** include a stable server identity in media identity, progress and recents. Define a migration for existing path-only records; prefer a stable server ID if the protocol supplies one, with a normalized endpoint as a fallback. Centralize construction instead of interpolating progress keys throughout the app.

### F06 — P2: Up can race a pending directory load

**Evidence: static asynchronous control-flow inspection.**

Location: [RelayViewModel.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayViewModel.kt#L1244-L1249), lines 1244–1249 and 1298–1305; [RelayApp.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayApp.kt#L698-L699), lines 646–651 and 698–699.

While browsing `A`, open `A/B`, then press Up or system Back before the request finishes. Up immediately changes the current directory/stack and remains enabled while loading. On completion, `openDirectory()` appends whichever directory is current at that moment and installs `A/B`, without checking whether the navigation request is still current. The user is sent back into the child and its recorded parent can become the root rather than `A`.

**Recommendation:** give directory navigation an identity/generation and discard or cancel superseded results. Preserve usable navigation during quiet refresh; do not solve this by disabling the whole browser whenever any background connection runs. Add a delayed-response navigation regression.

### F07 — P2: changing sort leaves ancestor listings and cursors stale

**Evidence: static state inspection; server cursor error behavior not reproduced.**

Location: [RelayViewModel.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayViewModel.kt#L833-L838), lines 833–838 and 1298–1305.

Enter a child folder, change A–Z to Newest, then return Up. Sort refresh replaces only the current directory. Up restores the parent snapshot and paging cursor saved under the previous sort, while the global sort toggle still says Newest. The parent ordering is incorrect, and further pagination sends an old-order cursor alongside the new sort. The exact paging failure depends on server cursor semantics.

**Recommendation:** include sort in each directory snapshot/cursor identity and invalidate or refetch mismatched ancestors. Test changing sort in a child, returning to a paginated parent, and loading another page.

### F08 — P2: Settings does not display connection errors

**Evidence: reproduced on the tablet.**

Location: [RelayApp.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayApp.kt#L1005-L1026), lines 1005–1026; [RelayViewModel.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayViewModel.kt#L1015-L1019), lines 1015–1019.

Settings has its own Connect form but never displays `state.error`. Entering port `0` and pressing Connect appeared to do nothing. Switching to Server immediately displayed “Enter a valid host and port.” The error existed but was invisible on the screen where the action was initiated. Connection failures have the same presentation gap. [Screenshot](audit-2026-10-09/settings-invalid-port.png).

**Recommendation:** share connection-form validation and error presentation across destinations. Mark the invalid field, show a concise inline message, and expose the result to accessibility services. Add a Compose test for invalid input and a failed connection while Settings remains selected.

### F09 — P2: controls disappear during an active seek-bar drag

**Evidence: reproduced with an eight-second tablet drag.**

Location: [RelayApp.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayApp.kt#L1430-L1439), lines 1430–1439 and 2002–2017.

The auto-hide timer observes pressed icon controls and committed `state.seeking`, but not the custom bar's local `dragging` state or `seekPreviewSeconds`. During an eight-second horizontal drag on the seek bar, a screenshot taken approximately five seconds into the gesture and the UI hierarchy showed the controls had disappeared while the drag was still active. Removing `PlayerChrome` interrupts the interaction.

**Recommendation:** hoist a shared active-interaction flag, or explicitly suspend the timeout during scrubbing and other continuous gestures. Restart the timeout after release/cancellation. Retain the existing accessibility-adjusted timeout. Add coverage for a drag longer than the timeout and for cancellation.

### F10 — P2: first-open local fallback has no playback identity

**Evidence: static failure-path inspection; not exercised by the existing successful-start fallback test.**

Location: [RelayViewModel.kt](../app/src/main/kotlin/org/upscalerelay/android/RelayViewModel.kt#L1495-L1506), lines 1495–1506 and 1921–1956.

When the local document/HTTP bridge opens but the initial relay preparation fails, the code retains the bridge so Play original is available. `activeOrigin` is assigned only in the relay success path (1472), and `playLocalFallback()` does not set it. On a fresh attempt it remains null. Direct playback can run, but progress saving and EOF navigation return early because both require an origin. Resume/history is lost, and autoplay-off cannot return to the library at EOF through that path.

**Recommendation:** establish the selected local media identity independently of relay success and preserve it through fallback. Test server rejection on the first local open, then direct playback, progress persistence and natural EOF. Keep this distinct from fallback after a previously successful relay session.

## Reuse, simplification and consistency opportunities

These are incremental changes to support the fixes, not a recommendation for a wholesale rewrite.

| Opportunity | Concrete change and benefit |
| --- | --- |
| Testable session orchestration | Extract/inject controller and player factories, network state and a clock from `RelayViewModel`. Test operation ownership, cancellation and completion order without constructing Android/native services. Then move connection/open/recovery coordination into a small owner with explicit operations. |
| One preference mapping | Introduce a tested preferences-to-UI mapping. The field inventory currently repeats across preferences, UI defaults/restoration and backup encoding; F04 is a concrete omission caused by that repetition. Keep persisted intent separate from runtime status. |
| One browser snapshot model | Store origin, directory path, sort, page cursor and navigation generation together. Reuse it for Up, refresh, restore and pagination, addressing F06/F07. Reuse page iteration for previous/next-file searches without hiding their ordering policy. |
| One media identity | Give server and local sources explicit identity types. Reuse them in history, recents, watched state, fallback and progress persistence instead of scattered string concatenation; this supports F05/F10. |
| Shared connection and playback controls | Extract a connection form with validation/error status. Reuse quality, fit and resize selectors between Settings and player sheets, retaining layout differences appropriate to each destination. |
| Shared transport construction | `RelaySessionController` repeats negotiation/model validation at 143–164 and 285–297, plus queue/listener/downlink setup at 215–252 and 310–344. Extract focused helpers while retaining source-specific uplink/attachment ownership. |
| Named timeout policy | Distinguish connection, media handshake, preparation inactivity, player attachment and teardown deadlines. Their different meanings matter; centralizing names/composition would expose mismatches like F03 without inventing a generic retry framework. |
| Validated attachment manifest | `Messages.kt:309–327` and `AttachmentCache.kt:61–73` repeat validation with different duplicate-byte accounting. The parser totals unique hashes; cache/server policy totals entries. Use one representation with explicit entry count, duplicate consistency and total/unique byte limits. This is a consistency opportunity, not a demonstrated current-server failure. |
| Injectable native facade | A small interface around the JNI singleton would make command ordering, attachment holds, stale callbacks and close/initialize ownership testable. Preserve the process-global native ownership barrier. |
| Typed playback termination | Preserve native EOF/stop/error reasons instead of mapping every `END_FILE` to `ENDED` and reconstructing intent from position/connection heuristics in the ViewModel. Existing near-end guards were checked; this is not counted as another confirmed defect. |
| Shared document access primitives | Centralize descriptor ownership, seekability, length and cancellation semantics between extractor and local HTTP access. Keep independent descriptors per reader and preserve asset start offsets. Avoid assuming all SAF providers behave like local files. |
| Small cleanup work | Share bounded EBML/VINT decoding primitives, remove unused `RememberedTrack.priorId`, and remove provably obsolete minimum-SDK branches when touching those areas. Reconcile stale documentation about reconnect loops, compile SDK and compact-device validation. |

Keep the existing source-specific mpv rules: confirmed muxed epochs never add `/media`; external media attaches once after restart; subtitle-only sources need no audio wait; pause intent survives reload; teardown waits for the actual native-cleanup acknowledgement. Simplification must preserve those measured behaviors.

## UI review and improvement opportunities

The device review covered the actual tablet's portrait browser and landscape Settings, Recent and player screens. These observations combine screenshots, accessibility hierarchy inspection, interactions and source review. Compact/large-font observations below are source-based, not physical-phone validation.

| Area | Observation | Suggested improvement |
| --- | --- | --- |
| Settings density | In landscape, the connection form spans almost the entire 14.6-inch display; expanded quality and resize choices require several screenfuls of scrolling. [Observed layout/error example](audit-2026-10-09/settings-invalid-port.png). | Use a bounded content width or two logical columns on wide windows. Show current quality/filter values in compact selectors that open a choice sheet. Keep common controls near the top and advanced rendering options grouped. |
| Empty detail pane | The portrait library reserves a substantial right pane even before a file is selected, containing only a vertically centered prompt. [Portrait screenshot](audit-2026-10-09/library-portrait.png). | Collapse the detail pane until selection, or fill it with useful resume/recent/context information. Preserve list/detail on widths where both panes provide useful content. |
| Connection feedback | Settings currently hides errors (F08), while a cached disconnected listing can look much like a live one. | Show a small connected/cached/offline status and an action-specific error. Preserve the deliberately quiet reconnect behavior; do not add blocking overlays for background work. |
| Resume action | A generic Play action can silently apply a saved position. | Offer “Resume at …” and “Start over”, together with watched/progress context. Keep the primary action predictable. |
| Watched state | Mark watched/unwatched relies on an undisclosed long press; `fileCardClicks` supplies no descriptive long-click label. | Add an overflow action and accessibility action label; retain long press as a shortcut. |
| Recent source scope | Recent is server-only, while local recents live elsewhere. The destination subtitle explains this, but the navigation label is generic. | Unify recents with visible source identity, or label the destination “Server recents”. Include server identity when F05 is fixed. |
| Continuous interaction | Seek-bar drag can lose its controls (F09). Icon taps and custom gestures currently use different interaction bookkeeping. | Use one interaction policy across buttons, scrub bar, touch gestures and menus. Make paused/buffering/reconnecting state readable without obscuring the video. |
| Compact/large-font layout | The Settings connection row fixes the port field at 150 dp beside Connect, leaving the host to absorb all shrinking. History chips use a nonwrapping row. | Stack the connection form and wrap choices at narrow widths. Verify font scaling, short split-screen windows, keyboard visibility and insets on the resulting layouts. |
| Accessibility | Existing seek progress semantics and 48 dp seek target are useful; many choice rows still need review as unified selectable targets. | Add radio-group/selection semantics, audit focus order, TalkBack access to hidden controls and menu actions, and test increased text size. Do not equate hierarchy inspection with a TalkBack usability pass. |
| Local navigation consistency | Local list scroll restoration uses a display-name key, unlike URI identity used elsewhere; identically named folders can share scroll state. | Key saved scroll position by document/tree URI and use the same restoration policy as server browsing. |

## Validation performed

Host: Windows, installed Android Studio JBR, Android SDK, Gradle wrapper 9.7.1. No dependency upgrades or installer downloads were needed.

Build/lint command:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\b580v\AppData\Local\Android\Sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --no-daemon -PcoinstallDebug=true `
  :relay-protocol:test :relay-client:test :relay-demux:testDebugUnitTest `
  :player-mpv:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest
```

**Result: successful.** Unit test tasks were then explicitly re-executed with task-level `--rerun`, so the reported test result does not rely only on cached passes. [Fresh unit-test log](audit-2026-10-09/unit-tests.txt).

| Module | Tests | Failures/errors |
| --- | ---: | ---: |
| `relay-protocol` | 20 | 0 |
| `relay-client` | 39 | 0 |
| `relay-demux` | 9 | 0 |
| `player-mpv` | 11 | 0 |
| `app` | 31 | 0 |
| **Total** | **110** | **0** |

Lint: **zero errors, six warnings**: `ChromeOsAbiSupport`, `EmptySuperCall`, `GradleDependency`, `InsecureBaseConfiguration`, `ObsoleteSdkInt`, and `OldTargetApi`. The Gradle run also reports deprecations incompatible with Gradle 10. Warnings were reviewed as constraints/maintenance items; no blanket upgrade was attempted. The complete generated lint report remains at `app/build/reports/lint-results-debug.html` locally.

Device: Samsung Galaxy Tab S9 Ultra (`SM-X910`), Android 16/API 36. The separate `org.upscalerelay.android.debug` app was installed; the existing release app/data were not modified. A stale `.debug.test` package used a different signing key and was replaced before instrumentation.

**Four instrumentation tests passed:**

- Native owner handoff waits for the preceding mpv owner to close.
- Truncated media payload fails promptly.
- Activity/player close and immediate reopen, repeated three times.
- Live server playback: hardware MediaCodec assertion, subtitles-off, paused seek, paused settings restart, overlapping paused seeks, resume, and acknowledged close/return to browser.

[Lifecycle/framing results](audit-2026-10-09/device-lifecycle-framing.txt): three tests in 5.012 seconds. [Live playback result](audit-2026-10-09/device-playback.txt): one test in 17.202 seconds. Live playback used the existing LAN relay, `passthrough`, `hevc-qp18`, and an available 1080p library MKV. It changed only debug-client choices, not the relay's default model configuration.

Additional interactive checks reproduced F04, F08 and F09. Source/helper probing reproduced F02 and the listener timeout in F03. The invalid debug port was restored to 8590; playback was stopped. The final relay status showed **zero sessions**, `restart_required:false` and no native teardown error. That status is not independent proof of server filesystem/GPU resource cleanup.

### Limits and remaining gates

- No code fixes were applied, so passing existing tests does not resolve the findings.
- The five static-only defects need focused reproductions/regressions. The timeout probe does not replace a slow-server/device test, and the NAL probe does not measure actual decoder symptoms.
- This run did not exercise local SAF relay/direct fallback, natural full-file EOF, long-duration playback, repeated-session leak/thermal/battery behavior, screen-off/PiP recovery, bitmap PGS/VobSub subtitles, hostile/cloud document providers, S Pen/DeX, physical phones or a full TalkBack pass.
- Test portrait camera rotation and anamorphic pixel aspect in both local relay and direct-original modes. Current local video metadata carries coded dimensions but no explicit rotation handling; this is an unverified graphical pipeline gap, not an additional confirmed defect. Any needed protocol extension should be additive and negotiated.
- No fresh build of the native mpv/FFmpeg sources, native dependency vulnerability audit, release signing/publication, redistribution review, or exhaustive security assessment was performed. Cleartext trusted-LAN transport and arm64-only binaries remain documented project constraints.
- The existing device fallback test starts with successful relay playback; it does not cover F10. The host suite has no composed `RelaySessionController` or ViewModel orchestration tests. HTTP cancellation tests should also exercise real blocked response bodies, not only cooperative fakes.

## Suggested implementation order

1. Add deterministic orchestration seams/regressions and fix F01; preserve the quiet cached-library UX.
2. Fix NAL framing and loopback deadline composition (F02/F03) with focused boundary/composition tests.
3. Fix preference and source-identity bookkeeping (F04/F05/F10), defining history migration explicitly.
4. Make browser snapshots/navigation requests generation- and sort-aware (F06/F07).
5. Fix Settings feedback and continuous player interactions (F08/F09), then apply the shared UI components and tablet density improvements.
6. Run targeted failure-path device checks, followed by the still-open endurance, local-provider, PiP and accessibility gates. Keep this acceptance work separate from ordinary unit-test success.
