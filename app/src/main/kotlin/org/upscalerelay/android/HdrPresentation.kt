package org.upscalerelay.android

import org.upscalerelay.player.mpv.VideoOutput
import org.upscalerelay.protocol.DisplayTarget
import org.upscalerelay.protocol.SessionInfo
import kotlin.math.roundToInt

/*
 * When an HDR video is shown as HDR (docs/HDR_OUTPUT.md, "When display_target
 * should be pq"), apart from the Android calls that answer each condition
 * (MainActivity reads the display, RelayViewModel the power saver).
 */

/**
 * What the Activity's display can show as HDR: whether its HDR types,
 * without those the user turned off, include HDR10 or HLG, and the peak an
 * HDR picture should be fitted to (`desiredMaxLuminance`, 540 nits on the
 * Tab S9 Ultra), null when it reports none that is usable.
 */
data class DisplayHdr(val hdr: Boolean = false, val peakNits: Double? = null) {
    companion object {
        /** An SDR display, and what the app assumes until the Activity has read its own. */
        val SDR = DisplayHdr()
    }
}

/** The first Android with the HDR relay (SurfaceControl.Transaction.setBuffer and setDataSpace). */
internal const val HDR_OUTPUT_MIN_SDK = 33

/**
 * The display named to the server (PROTOCOL.md §3.6) for a source that is
 * HDR or not ([sourceHdr]; null before the session says, which counts as
 * HDR). `pq` with the display's peak while the picture can be shown as HDR:
 *
 * - the display shows HDR10 or HLG ([display]),
 * - "Show HDR videos in HDR" is on ([showHdr]),
 * - the HDR relay exists here ([sdkInt]),
 * - the power saver is off ([powerSave]): it turns off the panel's HDR
 *   boost (`allowInLowPowerMode false`), so highlights would get no room.
 *
 * `sdr` anywhere else, so the server maps Dolby Vision and HDR10+ for SDR.
 * No SDR white either way: Android reports none, and the panel keeps SDR
 * white at 203 nits under the brightness slider. Picture-in-picture is not a
 * condition: the panel drops its boost for the small window, but the PQ
 * picture still plays correctly there, and a switch would cost a reload as
 * the window shrinks.
 */
internal fun displayTargetFor(
    sourceHdr: Boolean?,
    display: DisplayHdr,
    showHdr: Boolean,
    sdkInt: Int,
    powerSave: Boolean,
): DisplayTarget =
    if (sourceHdr != false && display.hdr && showHdr && sdkInt >= HDR_OUTPUT_MIN_SDK && !powerSave) {
        DisplayTarget("pq", display.peakNits)
    } else {
        DisplayTarget.SDR
    }

/**
 * How mpv presents [epoch] for [target]: PQ when the epoch itself is HDR
 * (its own `downlink_color`) and the display shows HDR. An SDR epoch, a
 * mapped one the server made for an SDR display included, and an epoch whose
 * colour the server does not know play on the SDR output, where mpv
 * tone-maps whatever it finds.
 */
internal fun videoOutputFor(target: DisplayTarget, epoch: SessionInfo): VideoOutput =
    if (target.transfer == "pq" && epoch.downlinkColor?.isHdr == true) {
        VideoOutput.Pq(target.peakNits)
    } else {
        VideoOutput.Sdr
    }

/**
 * Whether the epoch playing no longer fits [target], so a reload at the
 * current position has to replace it: the server made a mapped epoch for
 * another display, or mpv presents it ([presented], what its load asked for)
 * another way than the display now calls for. The battery saver coming on,
 * the window moving to an SDR monitor and the setting switched off all end
 * here, and so do their opposites.
 */
internal fun hdrPresentationStale(target: DisplayTarget, epoch: SessionInfo, presented: VideoOutput): Boolean {
    val madeFor = epoch.downlinkMapping?.transfer
    if (madeFor != null && madeFor != target.transfer) return true
    return videoOutputFor(target, epoch) != presented
}

/** For logs and diagnostics: "SDR", "PQ to 540 nits". */
internal fun VideoOutput.describe(): String = when (this) {
    VideoOutput.Sdr -> "SDR"
    is VideoOutput.Pq -> peakNits?.let { "PQ to ${it.roundToInt()} nits" } ?: "PQ"
}

/**
 * [DisplayHdr] from what Android reports for the display: its HDR types
 * (`Display.HdrCapabilities.HDR_TYPE_*`, without those the user turned off)
 * and `desiredMaxLuminance`. A peak outside what the server and mpv take
 * (10-10000 nits) is no peak.
 */
internal fun displayHdrFrom(hdrTypes: IntArray?, desiredMaxLuminance: Float): DisplayHdr {
    val types = hdrTypes ?: return DisplayHdr.SDR
    val hdr = HDR_TYPE_HDR10 in types || HDR_TYPE_HLG in types
    val peak = desiredMaxLuminance.toDouble().takeIf { it.isFinite() && it >= 10.0 && it <= 10_000.0 }
    return if (hdr) DisplayHdr(true, peak) else DisplayHdr.SDR
}

// Display.HdrCapabilities' constants, here so that the rule above runs in a JVM test.
private const val HDR_TYPE_HDR10 = 2
private const val HDR_TYPE_HLG = 3
