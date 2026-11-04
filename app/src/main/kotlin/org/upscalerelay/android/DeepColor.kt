package org.upscalerelay.android

import org.upscalerelay.protocol.HighBitDepth

/*
 * What the app asks of a deep-colour server (PROTOCOL.md §3.5), apart from
 * the Android calls that answer [decodesMain10] (decodesHevcMain10).
 */

/**
 * The deep colour to ask for: HDR sources at 10 bits with their metadata,
 * as to the desktop, and under "Use 10 bit video" every other source too.
 * Only where a hardware decoder takes the 10-bit downlink; elsewhere
 * nothing, so the session stays 8-bit and names no display either (the
 * server maps only deep colour).
 */
internal fun downlinkDepthFor(tenBitVideo: Boolean, decodesMain10: Boolean): HighBitDepth? =
    (if (tenBitVideo) HighBitDepth.ALL else HighBitDepth.HDR).takeIf { decodesMain10 }

/**
 * Whether toggling "Use 10 bit video" changes the stream playing now: a
 * relay stream of an SDR source, from a server with deep colour, on a device
 * that decodes it. An HDR source is 10-bit either way (a mapped one in its SDR
 * epochs too), and the original file playing directly has no downlink.
 */
internal fun RelayUiState.tenBitVideoChangesStream(decodesMain10: Boolean): Boolean {
    val session = relaySession ?: return false
    return !session.hdrSource && capabilities?.highBitDepth == true && decodesMain10
}
