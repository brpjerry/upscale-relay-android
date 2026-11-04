package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.upscalerelay.protocol.DownlinkMapping
import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.VideoColor

class PictureFormatTest {
    private val pq = VideoColor(primaries = 9, transfer = 16, matrix = 9, range = "limited")
    private val hlg = VideoColor(primaries = 9, transfer = 18, matrix = 9, range = "limited")
    private val bt709 = VideoColor(primaries = 1, transfer = 1, matrix = 1, range = "limited")

    private fun session(
        depth: Int = 8,
        color: VideoColor? = null,
        mapping: DownlinkMapping? = null,
    ) = SessionInfo(
        sessionId = "s",
        mediaPort = 8591,
        uplinkToken = null,
        downlinkToken = "t",
        downlinkCodec = "hevc",
        downlinkWidth = 2960,
        downlinkHeight = 1848,
        downlinkContainer = "matroska",
        epoch = 0,
        durationSeconds = null,
        timeBase = null,
        averageRate = null,
        fitMode = "fit",
        resizeAlgorithm = null,
        downlinkBitDepth = depth,
        downlinkColor = color,
        downlinkMapping = mapping,
    )

    private fun mapped(vararg applied: String) = DownlinkMapping("sdr", null, null, applied.toList())

    @Test
    fun `an 8-bit SDR stream adds nothing to the top bar`() {
        assertNull(session().pictureSummary())
        assertNull(session(color = bt709).pictureSummary())
        assertEquals("8-bit colour unknown", session().pictureDiagnostics())
        assertEquals("8-bit SDR", session(color = bt709).pictureDiagnostics())
    }

    @Test
    fun `deep colour and HDR are named by depth and transfer`() {
        assertEquals("10-bit HDR", session(10, pq).pictureSummary())
        assertEquals("10-bit PQ", session(10, pq).pictureDiagnostics())
        assertEquals("10-bit HLG", session(10, hlg).pictureDiagnostics())
        assertEquals("10-bit", session(10, bt709).pictureSummary())
        // A tier with no 10-bit encoder keeps an HDR source's tags at 8 bits.
        assertEquals("8-bit HDR", session(8, pq).pictureSummary())
    }

    @Test
    fun `what the server applied is said as the desktop says it`() {
        val dolbyVision = session(10, bt709, mapped("dolby_vision"))
        assertEquals("10-bit · Dolby Vision applied by the server", dolbyVision.pictureSummary())
        assertEquals("10-bit SDR (Dolby Vision mapped)", dolbyVision.pictureDiagnostics())
        assertEquals(
            "Dolby Vision and HDR10+",
            session(10, bt709, mapped("dolby_vision", "hdr10plus")).appliedDynamicHdr(),
        )
        assertEquals("future_metadata", session(10, bt709, mapped("future_metadata")).appliedDynamicHdr())
        // A mapped session's epoch that applied nothing streams as it is.
        assertEquals("10-bit HDR", session(10, pq, mapped()).pictureSummary())
        assertNull(session(10, pq, mapped()).appliedDynamicHdr())
    }
}
