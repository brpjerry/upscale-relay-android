# HDR output on Android

The relay already carries HDR end to end: a capable server sends 10-bit PQ or
HLG with its metadata (PROTOCOL §3.5), and the client names its display
(§3.6). This document covers what is still missing: showing that signal as
HDR on the Galaxy Tab S9 Ultra's panel instead of mpv tone-mapping it to SDR.
It is the result of a spike (2026-11-03). Nothing here is shipped yet:
`PLAYER_DISPLAY` on main is still `sdr`.

## Recommendation

**Keep mpv's `gpu` renderer and give it an HDR surface (approach 3).** mpv
renders PQ into a 10-bit window (`--egl-output-format=rgb10_a2`,
`target-trc=pq`, `target-prim=bt.2020`, `target-peak=<display peak>`). The app
then has to tag that output as BT.2020 PQ, because neither mpv nor a Java call
on the `SurfaceView` can do it. The variant that works without an NDK, and
was measured, is an **ImageReader relay**:

- mpv renders into an `ImageReader` whose default dataspace is BT2020_PQ.
- The app hands each buffer to a child `SurfaceControl` of the `SurfaceView`,
  with that dataspace (API 33+).

On the tablet this put a 2960x1848 RGBA_1010102 PQ layer on screen and
DisplayManager switched the panel to HDR brightness. The hardware composer
took the layer as an overlay. There were no drops, startup and seeks did not
change, and the GPU did the same work as today's SDR path. mpv keeps drawing
subtitles, OSD, deband and its scalers, so nothing the app relies on breaks.

The two other approaches lose more than they gain:

- **`vo=mediacodec_embed` (approach 2)** is the cheapest HDR there is: the
  decoder writes straight to the layer with the stream's own metadata, and
  the GPU sits idle. But mpv then draws nothing, no subtitles above all, and
  the library is mostly anime with ASS subtitles. Replacing mpv's subtitle
  stack (libass, fonts, attachments, bitmap formats) costs more than the whole
  HDR feature. It also drops to software decoding whenever the Surface goes
  away.
- **A Vulkan libmpv (approach 1)** cannot keep 10 bits. mpv's MediaCodec
  interop on Android (`hwdec_aimagereader`) is GL-only, so Vulkan would have
  to decode with `mediacodec-copy`, and that hands mpv **`nv12`**, 8 bits, for
  the 10-bit PQ downlink (measured). It also needs a custom native build that
  upstream mpv-android does not do (`-Dvulkan=disabled` to this day). Its one
  advantage, gpu-next applying Dolby Vision and HDR10+ itself, is moot here:
  the server maps Dolby Vision for the display it is told about, and the
  panel has no Dolby Vision.

Before this ships, someone has to look at the panel (see "Still open").

## What the tablet does with HDR

Galaxy Tab S9 Ultra `SM-X910`, Android 16 (API 36), build `X910XXS6EZI2`,
Adreno 740.

- `Display.getHdrCapabilities()`: HDR10, HLG and HDR10+ (types 2, 3, 4), max
  luminance 540 nits, max average 270, min 0.2. `hdrSdrRatio` is
  `not_available`, and no SDR white level is reported.
- The platform EGL offers `EGL_EXT_gl_colorspace_bt2020_pq`, `_hlg`,
  `_linear`, `EGL_EXT_surface_SMPTE2086_metadata` and
  `EGL_EXT_surface_CTA861_3_metadata` (listed in `dumpsys SurfaceFlinger`).
- **HDR follows the brightness slider.** While an HDR layer covers the screen,
  DisplayManager logs `hbmMode=hdr` and raises the backlight to about 2.64x
  the current SDR level, whatever that is. Measured pairs (SDR nits → HDR
  nits): 57.6 → 151.9 in the dark, 97.6 → 259.9, 174.4 → 455.8,
  188.4 → 484.0. 2.64 is 540/203: SDR white is treated as a 203-nit
  reference, and the panel's 540-nit peak is only reached near full
  brightness.
- The HDR mode needs the HDR layer to cover half the screen
  (`minimumHdrPercentOfScreenForHbm 0.5`). It is off in battery saver
  (`HdrBrightnessData.allowInLowPowerMode false`).
- SurfaceFlinger does no SDR dimming of its own (`whitePointNits=-1`,
  `dimmingRatio=1` on every layer): Samsung's display pipeline balances the
  UI against the HDR layer.

## How it was measured

Prototype branch `spike/android-hdr-output-prototype` (not for merging):

- **Options file.** `files/mpv-spike.conf` adds mpv options after the
  engine's own and steers the prototype (`spike.tag`, `spike.display`,
  `spike.headroom`).
- **Device test.** `HdrOutputSpikeDeviceTest` connects to the relay on
  `passthrough`, `hevc-qp4`, cover (2960x1848). It plays a server file and
  records:
  - startup: from the open to a moving picture;
  - drops over 25 s;
  - two seeks: latency to a moving picture at the target, and drops over the
    next 8 s;
  - the video layer from `dumpsys SurfaceFlinger`: buffer format, dataspace,
    HDR metadata types, composition type and the "HDR events" log;
  - `mIsHdrLayerPresent` and the `BrightnessEvent` log from `dumpsys display`.
- **Load sampler.** A host script read `kgsl` GPU busy %, the GPU clock and
  the app's CPU % once a second over 15 s of steady playback.

The tablet was locked with a PIN, so the test activity showed over the
keyguard (`setShowWhenLocked`). That has two consequences. Nobody looked at
the panel during these runs. And picture-in-picture could not be entered
(the layer stayed 2960x1848), so every PiP step below is **not measured**.

### Results

Cyberpunk Edgerunners S02E01, 2160p Dolby Vision 8.1 + HDR10, 23.976 fps, 14
audio and 59 SubRip tracks (English subtitles on). With `pq`, the server sent
the 10-bit PQ base with the RPU unapplied. With `sdr`, it mapped Dolby Vision
to 10-bit BT.709.

| Path | Display named | Video layer | HDR on the panel | Startup | Seek to 10:00 / 2:00 | Drops | GPU busy (clock) | App CPU |
|---|---|---|---|---|---|---|---|---|
| today: `gpu`, 8-bit | sdr | RGBx_8888, V0_SRGB | no | 4.1 s, 4.5 s | 6.7 / 4.5 s | 0 | 27 % (475 MHz) | 32 % |
| 2: `mediacodec_embed` | pq | vendor 10-bit (`0x7fa30c09`), BT2020_ITU_PQ, metadata types 3 | **yes** | 5.9 s, 2.8 s | 7.4 / 4.4 s | 0 | 0.1 % (220 MHz) | 33 % |
| 3, untagged: `gpu`, rgb10_a2, PQ out | pq | RGBA_1010102, V0_SRGB | no (PQ read as sRGB) | 6.5 s | 7.6 / 4.4 s | 0 | 62 % (220 MHz) | 39 % |
| **3, relay** | pq | RGBA_1010102, BT2020_PQ, no metadata | **yes** | 6.2 s | 7.6 / 4.4 s | 0 | 62 % (220 MHz) | 39 % |
| 1's decode: `gpu` + `mediacodec-copy` | pq | RGBx_8888, V0_SRGB; frames arrive as **nv12** | no | 3.7 s | 7.3 / 4.6 s | 0 | 30 % (220 MHz) | 44 % |

- **Composition.** The hardware composer took every video layer as an
  overlay (`composition type=DEVICE`), the relay's child layer included.
- **HDR evidence.** Both HDR paths show `mIsHdrLayerPresent=true`,
  `numHdrLayers(1), size(2960x1848)` in SurfaceFlinger's HDR events, and a
  `BrightnessEvent ... [ hdr ], hbmMode=hdr`. None of the other runs show
  any of it.
- **Startup and seeks are network-bound.** Repeats of the same path differ
  more than the paths do: embed started in 5.9 and 2.8 s, today's path in
  4.1 and 4.5 s, and a seek to 15:00 took 4.4 s (today) and 4.3 s (embed).
  The renderer changes none of it.
- **GPU load.** The busy figure is relative to the clock the governor picked.
  Today's path ran at 475 MHz and both 10-bit PQ paths at the 220 MHz floor,
  so the work is about the same: busy x clock is 128 against 136. The relay
  adds nothing measurable over rendering PQ untagged.
- **A/V sync.** `avsync` stayed under 0.05 ms on every path, so the drift
  watchdog's input is unchanged. Embed reports it too.

The HDR test card (`HDR test card - 10000 nits.mkv`, HDR10, no audio) gave the
same picture. It takes 7-9 s to start on any path because it streams at
0.67 Mbps.

## Approach 1: a Vulkan libmpv (gpu-next, `androidvk`)

- mpv has an Android Vulkan context (`video/out/vulkan/context_android.c`),
  built only when mpv is built with Vulkan. It has no `set_color` of its
  own, so with `target-colorspace-hint` gpu-next calls
  `pl_swapchain_colorspace_hint` and libplacebo picks a matching swapchain
  colour space if the surface offers one. Whether Android's Vulkan offers
  HDR10 (ST 2084) on this tablet was not checked: there is no Vulkan build
  to check it with.
- **The decode path is the problem.** `hwdec_aimagereader.c` refuses any
  non-GL renderer (`if (!ra_is_gl(...)) return -1`). Under Vulkan, MediaCodec
  could only copy back. With `hwdec=mediacodec-copy` on the 10-bit downlink,
  mpv received `nv12` (8 bits): PQ in 8 bits bands, which is what the 10-bit
  downlink exists to avoid. There are no drops and the GPU and CPU are fine
  at this size, but the bits are gone.
- **The build.** mpv-android builds libplacebo with `-Dvulkan=disabled` and
  FFmpeg with `--disable-vulkan`, at the pinned commit and at HEAD
  (2026-10-30). A Vulkan build needs a shader compiler (glslang or shaderc)
  for Android and a Linux build host. `native/build_mpv_from_source.sh` notes
  upstream does not support WSL, though Docker is available here.
- **gpu-next's metadata handling** (Dolby Vision RPUs, HDR10+) does not help
  this setup. For a `pq` display the server maps Dolby Vision profile 5 and
  streams other profiles on their HDR10 base. The panel has no Dolby Vision.

Not recommended unless mpv gains a Vulkan import of MediaCodec's
`AHardwareBuffer`s.

## Approach 2: `vo=mediacodec_embed`

`vo=mediacodec_embed` with `hwdec=mediacodec` (the JNI already sets `wid` to
the Surface) works with the bundled libmpv:

- The decoder renders to the `SurfaceView` in a vendor 10-bit format.
- Codec2 tags the layer BT2020_ITU_PQ and attaches the stream's SMPTE 2086
  and CTA 861.3 metadata (types 3).
- The panel goes to HDR, and the GPU does nothing.
- HDR10+ dynamic metadata should reach the panel the same way. That is
  unmeasured: there is no HDR10+ fixture.

**What breaks.** `vo_mediacodec_embed.c` only releases each decoded buffer to
the Surface (`av_mediacodec_release_buffer(buffer, 1)`) and accepts nothing
but `IMGFMT_MEDIACODEC`. From that:

- **Subtitles and OSD.** mpv draws none. A subtitle stays "selected" and
  invisible. The next section weighs a replacement.
- **mpv's picture options.** No deband, scalers, interpolation,
  `video-sync=display-resample` or panscan. `MpvPlayerEngine.setDeband`,
  `setVideoSyncPreferences` and `setPanscan` would do nothing. The downlink
  is already the screen's size, so scaling itself is no loss.
- **No Surface, no hardware decoding.** The Surface is the decoder's output.
  When it went away (background playback), mpv fell back to software HEVC
  decoding (`hwdec-current=no`) and logged reference errors. The `gpu` path
  switches to `mediacodec-copy` instead and keeps hardware decoding. Getting
  the picture back means a new decoder on the new Surface, which is a
  reload.
- **Untested here:** PiP (keyguard), and whether mpv re-creates the decoder
  cleanly on a new Surface without a reload.

**A separate subtitle renderer** would have to cover what mpv does now:

- **Text subtitles.** mpv still decodes them. `sub-text` (plain) and
  `sub-text/ass` (the current event's ASS text) can be observed and drawn in
  Compose. That is enough for SubRip.
- **ASS subtitles** need libass with the script's styles and the file's
  fonts. That means a second demux of the subtitle stream (from `/media` or
  the local bridge), the attachment and font handling that CLAUDE.md's hard
  rules describe, and the timing kept against mpv's clock. mpv exposes
  neither the styles nor the fonts. Positioned signs and karaoke, which
  SubsPlease releases carry, are lost without it.
- **Bitmap subtitles** (PGS, VobSub) have no property at all. They would need
  their own decoder.

That is a second subtitle stack beside mpv's.

Usable only as a special case: an HDR video with no subtitle selected. Even
then every subtitle toggle would switch the video output, at the cost of a
reload. Not worth carrying.

## Approach 3: tag the Surface the app gives mpv

- **mpv's side works today.** `--egl-output-format=rgb10_a2` exists in the
  bundled libmpv. With `target-trc=pq`, `target-prim=bt.2020` and
  `target-peak=540` it renders PQ into an RGBA_1010102 window at 2960x1848
  with no drops. `dither-depth=auto` then dithers to 10 bits. mpv's Android
  GL context (`context_android.c`) passes no attributes to
  `eglCreateWindowSurface` and ignores `target-colorspace-hint`. The window's
  dataspace stays unknown, which SurfaceFlinger shows as V0_SRGB, and the PQ
  code values are shown as sRGB.
- **`Window.setColorMode`** (not tried) sets the colour mode of the window's
  own HWUI surface. The video is on a separate `SurfaceView` layer, so it
  cannot tag the video.
- **Tagging the `SurfaceView` from Java does not work.**
  `SurfaceView.getSurfaceControl()` is the view's container layer.
  `SurfaceControl.Transaction.setDataSpace(BT2020_PQ)` landed there (a layer
  with no buffer), while the BLAST layer underneath, which receives mpv's
  buffers, stayed V0_SRGB. Java cannot reach that BLAST layer. AOSP's
  `BLASTBufferQueue` also sets the layer's dataspace from every buffer it
  queues, so a tag there would not stick either.
- **The ImageReader relay works.**
  - An `ImageReader` (RGBA_1010102; GPU colour output, sampled image and
    composer overlay usage; 5 images) gets `setDefaultDataSpace(BT2020_PQ)`.
  - mpv renders into its Surface. EGL queues buffers with an unknown
    dataspace, and the BufferQueue replaces that with the consumer's
    default.
  - On a handler thread the app takes each image and calls
    `Transaction.setBuffer(child, hardwareBuffer, fence, release)` plus
    `setDataSpace` on a child `SurfaceControl` of the `SurfaceView`. The
    release callback waits on SurfaceFlinger's release fence before closing
    the image.
  - Measured: the result in the table, an RGBA_1010102 BT2020_PQ overlay and
    HDR on the panel. `Image.getFormat()` reports 1 for these buffers, but
    the compositor's table shows `0x2b`.
- **No static metadata from Java.**
  - The only range control is `setDesiredHdrHeadroom` (API 35). With 2.66
    (540/203), SurfaceFlinger's HDR events read `desiredRatio(2.66)` instead
    of 49.26 (the PQ ceiling over 203). Samsung's brightness policy ignored
    it: the boost was the same 151.9 nits.
  - Whether the panel's own tone mapping treats an untagged-range PQ layer
    differently from embed's (which carries the stream's 10000-nit mastering
    data) is for the eye check below.
  - If it matters, `ASurfaceTransaction_setHdrMetadata_smpte2086` and
    `_cta861_3` (NDK) on the same child layer would carry the target's
    range.
- **Native alternatives, not tried here (no NDK installed):**
  - JNI `ANativeWindow_setBuffersDataSpace` on mpv's own window. AOSP's EGL
    loader sets the dataspace when it creates the surface (from
    `EGL_GL_COLORSPACE_KHR`, unknown by default), so this has to run after
    every mpv VO init, on a signal from mpv's thread. It saves the relay hop, which
    measured as free anyway, and adds the project's first NDK build.
  - Patch mpv's `context_android.c` to create the surface with
    `EGL_GL_COLORSPACE_BT2020_PQ_EXT`, and set the SMPTE 2086 surface
    attributes from `target-peak`, when `target-colorspace-hint` asks for
    PQ. This is the clean fix and worth offering upstream. Until upstream
    ships it, it means a custom libmpv build instead of the pinned APK's.

### Shipping approach 3 (sketch, not built)

- **Surface.** `MpvSurfaceView` owns the relay: reader, child layer and
  handler thread, all at the Surface's size. It gives mpv the reader's
  Surface only while the output is PQ. API 33 or later, else stay SDR.
- **Mode switches.** `egl-output-format` and the `target-*` options take
  effect when the VO is re-created. Switch them only between loads, the way
  the desktop moves its render context. Each relay seek is already a reload.
- **Teardown.** The reader is a new owner under the hard rule on retiring
  owners: detach mpv (`vo=null`) and await the command queue before closing
  the reader and releasing the child layer.
- **Subtitles and OSD.** mpv draws them into the PQ output at its SDR
  reference white. They should match the interface's white, but nobody has
  looked yet.

## Upgrading mpv-android (2026-04-25 → 2026-09-17)

Changes nothing for HDR. Between the pinned mpv (`v0.41.0-556-g9ce79bcaa`)
and mid-September:

- The Android GL context, `vo_mediacodec_embed` and the EGL helpers are
  untouched.
- `hwdec_aimagereader` only got better error handling and `maxImages` 3.
- mpv-android's build still disables Vulkan (also at HEAD, 2026-10-30). Its
  other changes are library bumps (FFmpeg n9.0.2, NDK), target API 36 and
  UI work.

Upgrade for its own sake, not for this.

## When `display_target` should be `pq`

Name `pq` only while the picture is actually presented as HDR. When any
condition flips, go back to `sdr` (and to the 8-bit tone-mapped surface)
through the same reload at the current position that the desktop uses. A
seek took 4.3-7.6 s on this link.

**`pq`, with the peak from `Display.getHdrCapabilities().desiredMaxLuminance`
(540 here), when all of these hold:**

- The source is HDR (`SessionInfo.hdrSource`).
- The Activity's current display (`Activity.display`) lists HDR10 or HLG in
  its supported HDR types, and the user has not disabled them
  (`userDisabledHdrTypes`).
- The device runs API 33 or later (the relay).
- A "Show HDR videos in HDR" setting is on, as on the desktop.

Leave `sdr_white_nits` out: Android reports no SDR white, and the panel
anchors SDR white to 203 nits under the slider. mpv takes the same peak as
`target-peak`.

**Back to `sdr`:**

- **Battery saver** (`PowerManager.isPowerSaveMode`, and its broadcast). The
  HDR boost is disabled there (`allowInLowPowerMode false`), so highlights
  would not get the headroom. Unmeasured.
- **The window moves to a display without HDR**, such as DeX on an SDR
  monitor or a cast. Re-check on `DisplayManager.DisplayListener` /
  `onConfigurationChanged` and on each open and seek. An HDR monitor keeps
  `pq` with its own peak. Unmeasured: no external monitor was attached.
- **PiP: decide after measuring.** The boost needs the HDR layer on half the
  screen, so a PiP window will not get it. Whether a PQ layer then looks
  wrong, or only dim, has to be seen. Every switch costs a reload as the
  window shrinks, so stay `pq` unless it looks wrong. The relay itself
  presented fine wherever the layer was shown.
- **Anything else** that ends HDR presentation: the setting is turned off,
  or the display's HDR capabilities change.

## Still open

- **The eye check.** Someone has to unlock the tablet and look, on the HDR
  test card, comparing the relay and `mediacodec_embed`:
  - Patches above SDR white should be brighter than the interface's white.
  - 203 nits should match the interface's white.
  - 540 and above should bunch at the top (mpv tone-maps above 540).
  - Subtitles in the PQ output should match the interface's white.
  - The relay's missing static metadata must not dim the picture.
  Then run the same check in PiP.
- **Not measured** (keyguard or fixtures):
  - PiP;
  - background and return;
  - battery saver;
  - DeX;
  - HLG and HDR10+ through each path.
- **Pre-existing, not HDR** (seen on today's path too, under the test with
  the keyguard up). With background playback on, the drift watchdog reloaded
  the epoch ("resyncing playback: 4.7s A/V error") while no Surface existed.
  The load waited for a Surface, and the controller failed after 30 s with
  `CONNECT_TIMEOUT`. On return the player sat in LOADING. Worth checking by
  hand on an unlocked tablet.
