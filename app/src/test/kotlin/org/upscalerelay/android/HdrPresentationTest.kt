package org.upscalerelay.android

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.player.mpv.VideoOutput
import org.upscalerelay.protocol.DisplayTarget
import org.upscalerelay.protocol.DownlinkMapping
import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.VideoColor

class HdrPresentationTest {
    private val tablet = DisplayHdr(hdr = true, peakNits = 540.0)
    private val pq540 = DisplayTarget("pq", 540.0)

    private val pq = VideoColor(primaries = 9, transfer = 16, matrix = 9)
    private val hlg = VideoColor(primaries = 9, transfer = 18, matrix = 9)
    private val bt709 = VideoColor(primaries = 1, transfer = 1, matrix = 1)

    private fun target(
        sourceHdr: Boolean? = true,
        display: DisplayHdr = tablet,
        showHdr: Boolean = true,
        sdkInt: Int = 36,
        powerSave: Boolean = false,
    ) = displayTargetFor(sourceHdr, display, showHdr, sdkInt, powerSave)

    private fun epoch(color: VideoColor?, mapping: DownlinkMapping? = null) = SessionInfo(
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
        fitMode = "cover",
        resizeAlgorithm = null,
        downlinkBitDepth = 10,
        downlinkColor = color,
        downlinkMapping = mapping,
    )

    private fun madeFor(transfer: String, vararg applied: String) = DownlinkMapping(transfer, null, null, applied.toList())

    @Test
    fun `an HDR source on an HDR display names pq at the display's peak`() {
        assertEquals(pq540, target())
        // Before session_opened says, the source may be HDR: the open names
        // the display an HDR video would be shown on, as the desktop does.
        assertEquals(pq540, target(sourceHdr = null))
        // No SDR white: Android reports none, and the server assumes 203.
        assertEquals(Json.parseToJsonElement("""{"transfer":"pq","peak_nits":540.0}"""), target().toJson())
    }

    @Test
    fun `each condition alone keeps the display SDR`() {
        assertEquals(DisplayTarget.SDR, target(sourceHdr = false))
        assertEquals(DisplayTarget.SDR, target(display = DisplayHdr.SDR))
        assertEquals(DisplayTarget.SDR, target(showHdr = false))
        // The relay needs SurfaceControl.Transaction.setBuffer and setDataSpace.
        assertEquals(DisplayTarget.SDR, target(sdkInt = 32))
        assertEquals(pq540, target(sdkInt = 33))
        // The battery saver turns the panel's HDR boost off.
        assertEquals(DisplayTarget.SDR, target(powerSave = true))
    }

    @Test
    fun `a display with no usable peak still shows HDR, at mpv's own peak`() {
        val unknown = target(display = DisplayHdr(hdr = true, peakNits = null))
        assertEquals(DisplayTarget("pq"), unknown)
        assertEquals(Json.parseToJsonElement("""{"transfer":"pq"}"""), unknown.toJson())
        assertEquals(VideoOutput.Pq(null), videoOutputFor(unknown, epoch(pq)))
    }

    @Test
    fun `an epoch plays as PQ only when it is HDR and the display shows HDR`() {
        assertEquals(VideoOutput.Pq(540.0), videoOutputFor(pq540, epoch(pq)))
        // mpv converts HLG to the PQ output.
        assertEquals(VideoOutput.Pq(540.0), videoOutputFor(pq540, epoch(hlg)))
        assertEquals(VideoOutput.Sdr, videoOutputFor(pq540, epoch(bt709)))
        // A colour the server does not know plays as before: mpv tone-maps
        // whatever it finds.
        assertEquals(VideoOutput.Sdr, videoOutputFor(pq540, epoch(null)))
        // On an SDR display HDR is tone-mapped by mpv, or by the server for a
        // mapped source.
        assertEquals(VideoOutput.Sdr, videoOutputFor(DisplayTarget.SDR, epoch(pq)))
        assertEquals(VideoOutput.Sdr, videoOutputFor(DisplayTarget.SDR, epoch(bt709, madeFor("sdr", "dolby_vision"))))
        // A Dolby Vision profile 5 source mapped for pq streams PQ.
        assertEquals(VideoOutput.Pq(540.0), videoOutputFor(pq540, epoch(pq, madeFor("pq", "dolby_vision"))))
    }

    @Test
    fun `the battery saver coming on sends an HDR10 epoch back to SDR`() {
        val hdr10 = epoch(pq)
        val playing = videoOutputFor(pq540, hdr10)
        assertFalse(hdrPresentationStale(pq540, hdr10, playing))
        val saver = target(powerSave = true)
        assertTrue(hdrPresentationStale(saver, hdr10, playing))
        // After the reload mpv tone-maps it, which is right until it is off.
        assertFalse(hdrPresentationStale(saver, hdr10, VideoOutput.Sdr))
        assertTrue(hdrPresentationStale(pq540, hdr10, VideoOutput.Sdr))
    }

    @Test
    fun `a mapped epoch made for another display is replaced`() {
        // Dolby Vision 8.1 made for an SDR display: the server applied the
        // RPU and sent BT.709. On an HDR display it must send the HDR base.
        val forSdr = epoch(bt709, madeFor("sdr", "dolby_vision"))
        assertFalse(hdrPresentationStale(DisplayTarget.SDR, forSdr, VideoOutput.Sdr))
        assertTrue(hdrPresentationStale(pq540, forSdr, VideoOutput.Sdr))
        // And the other way round, when the setting is switched off.
        val forPq = epoch(pq, madeFor("pq"))
        assertFalse(hdrPresentationStale(pq540, forPq, VideoOutput.Pq(540.0)))
        assertTrue(hdrPresentationStale(target(showHdr = false), forPq, VideoOutput.Pq(540.0)))
    }

    @Test
    fun `an HDR monitor with another peak takes a reload, an SDR epoch never does`() {
        val monitor = target(display = DisplayHdr(hdr = true, peakNits = 1000.0))
        assertTrue(hdrPresentationStale(monitor, epoch(pq), VideoOutput.Pq(540.0)))
        assertFalse(hdrPresentationStale(monitor, epoch(pq), VideoOutput.Pq(1000.0)))
        for (display in listOf(target(), target(display = DisplayHdr.SDR), target(powerSave = true))) {
            assertFalse(hdrPresentationStale(display, epoch(bt709), VideoOutput.Sdr))
        }
    }

    @Test
    fun `the display shows HDR with HDR10 or HLG, without what the user turned off`() {
        // Tab S9 Ultra: HDR10, HLG, HDR10+ at 540 nits.
        assertEquals(tablet, displayHdrFrom(intArrayOf(2, 3, 4), 540f))
        assertEquals(DisplayHdr(true, 540.0), displayHdrFrom(intArrayOf(3), 540f))
        assertEquals(DisplayHdr(true, 540.0), displayHdrFrom(intArrayOf(2), 540f))
        // Dolby Vision or HDR10+ alone, an empty list, no capabilities: SDR.
        assertEquals(DisplayHdr.SDR, displayHdrFrom(intArrayOf(1, 4), 540f))
        assertEquals(DisplayHdr.SDR, displayHdrFrom(intArrayOf(), 540f))
        assertEquals(DisplayHdr.SDR, displayHdrFrom(null, 540f))
        // A peak mpv and the server would reject is no peak.
        assertEquals(DisplayHdr(true, null), displayHdrFrom(intArrayOf(2), 0f))
        assertEquals(DisplayHdr(true, null), displayHdrFrom(intArrayOf(2), Float.NaN))
        assertEquals(DisplayHdr(true, null), displayHdrFrom(intArrayOf(2), 20_000f))
    }

    @Test
    fun `outputs read in logs as they are`() {
        assertEquals("SDR", VideoOutput.Sdr.describe())
        assertEquals("PQ to 540 nits", VideoOutput.Pq(540.0).describe())
        assertEquals("PQ", VideoOutput.Pq(null).describe())
    }
}
