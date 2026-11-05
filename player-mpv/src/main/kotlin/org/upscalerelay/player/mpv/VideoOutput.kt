package org.upscalerelay.player.mpv

import kotlin.math.roundToInt

/**
 * How mpv presents the picture (docs/HDR_OUTPUT.md).
 *
 * [Sdr] is the 8-bit surface of the `SurfaceView`, where mpv's `gpu`
 * renderer tone-maps anything HDR. [Pq] is a 10-bit BT.2020 PQ picture from
 * `gpu-next`, which [MpvSurfaceView] relays to the compositor tagged as
 * BT2020_PQ (HdrSurfaceRelay). A change takes a new VO, so the engine
 * switches between loads only.
 */
sealed interface VideoOutput {
    data object Sdr : VideoOutput

    /**
     * PQ output fitted to [peakNits], the display's peak. Subtitles and the
     * OSD are drawn at that peak too: on the Tab S9 Ultra the interface is
     * boosted along with the picture, and subtitles at 203 nits looked grey
     * beside the controls (checked by eye, 2026-11-04). Null when the display
     * does not report a usable peak: mpv's own defaults then.
     */
    data class Pq(val peakNits: Double?) : VideoOutput
}

/**
 * The mpv options that differ between the outputs. Everything else (hwdec,
 * `profile=fast`, `dither=fruit`, `dither-depth=auto`, which dithers to the
 * 10 bits of the PQ surface) is the same for both. [defaults] are the
 * values mpv had at start-up, read back for the SDR output so that it is
 * exactly what played before HDR output existed.
 */
internal fun mpvOutputOptions(output: VideoOutput, defaults: Map<String, String>): List<Pair<String, String>> =
    when (output) {
        VideoOutput.Sdr -> OUTPUT_OPTIONS.map { name ->
            name to (defaults[name] ?: SDR_FALLBACKS.getValue(name))
        }
        is VideoOutput.Pq -> {
            val peak = output.peakNits?.let(::mpvNits)
            listOf(
                "egl-output-format" to "rgb10_a2",
                "target-trc" to "pq",
                "target-prim" to "bt.2020",
                "target-peak" to (peak ?: "auto"),
                "sub-hdr-peak" to (peak ?: defaults["sub-hdr-peak"] ?: SDR_FALLBACKS.getValue("sub-hdr-peak")),
                "image-subs-hdr-peak" to
                    (peak ?: defaults["image-subs-hdr-peak"] ?: SDR_FALLBACKS.getValue("image-subs-hdr-peak")),
            )
        }
    }

/**
 * `gpu-next` for PQ: `gpu` pins subtitle white at 203 nits, and on the tablet
 * gpu-next also did less GPU work (docs/HDR_OUTPUT.md). The SDR output keeps
 * the renderer it always had.
 */
internal fun mpvVideoOutput(output: VideoOutput): String = when (output) {
    VideoOutput.Sdr -> "gpu"
    is VideoOutput.Pq -> "gpu-next"
}

/** The options [mpvOutputOptions] sets, whose start-up values the SDR output restores. */
internal val OUTPUT_OPTIONS = listOf(
    "egl-output-format",
    "target-trc",
    "target-prim",
    "target-peak",
    "sub-hdr-peak",
    "image-subs-hdr-peak",
)

/** mpv's documented defaults, for a property it would not read back. */
private val SDR_FALLBACKS = mapOf(
    "egl-output-format" to "auto",
    "target-trc" to "auto",
    "target-prim" to "auto",
    "target-peak" to "auto",
    "sub-hdr-peak" to "sdr",
    "image-subs-hdr-peak" to "sdr",
)

/** A luminance as mpv takes it (10-10000 nits), or null for one it would reject. */
internal fun mpvNits(nits: Double): String? =
    nits.takeIf { it.isFinite() && it >= 10.0 && it <= 10_000.0 }?.roundToInt()?.toString()
