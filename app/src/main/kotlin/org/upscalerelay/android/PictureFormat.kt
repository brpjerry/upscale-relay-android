package org.upscalerelay.android

import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.VideoColor

/*
 * How the downlink's picture is encoded (PROTOCOL.md §3.5 and §3.6), in the
 * words the player shows. Both follow each epoch: a mapped session's
 * seek_ready can change the colour and what was applied.
 */

private val DYNAMIC_HDR_NAMES = mapOf("dolby_vision" to "Dolby Vision", "hdr10plus" to "HDR10+")

/** The dynamic HDR metadata the server applied to this epoch, by name; null when none. */
internal fun SessionInfo.appliedDynamicHdr(): String? =
    downlinkMapping?.applied?.takeIf { it.isNotEmpty() }
        ?.joinToString(" and ") { DYNAMIC_HDR_NAMES[it] ?: it }

/**
 * The top bar's word on the picture, after model, tier and size: its depth,
 * whether it is HDR, and what the server applied for this display (the
 * desktop info card's "Dynamic HDR" line). Null for an 8-bit SDR stream,
 * which is all a server from before deep colour sends.
 */
internal fun SessionInfo.pictureSummary(): String? {
    val hdr = downlinkColor?.isHdr == true
    val parts = buildList {
        if (downlinkBitDepth > 8 || hdr) add("$downlinkBitDepth-bit" + if (hdr) " HDR" else "")
        appliedDynamicHdr()?.let { add("$it applied by the server") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The diagnostics line's: depth, transfer and any mapping, e.g. "10-bit PQ" or "10-bit SDR (Dolby Vision mapped)". */
internal fun SessionInfo.pictureDiagnostics(): String = buildString {
    append("$downlinkBitDepth-bit ")
    append(downlinkColor?.transferName() ?: "colour unknown")
    appliedDynamicHdr()?.let { append(" ($it mapped)") }
}

private fun VideoColor.transferName(): String = when (transfer) {
    VideoColor.TRANSFER_PQ -> "PQ"
    VideoColor.TRANSFER_HLG -> "HLG"
    else -> "SDR"
}
