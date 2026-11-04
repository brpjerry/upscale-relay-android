package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.DownlinkMapping
import org.upscalerelay.protocol.HighBitDepth
import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.VideoColor

class DeepColorTest {
    @Test
    fun `the request follows the switch, and nothing is asked without a 10-bit decoder`() {
        assertEquals(HighBitDepth.HDR, downlinkDepthFor(tenBitVideo = false, decodesMain10 = true))
        assertEquals(HighBitDepth.ALL, downlinkDepthFor(tenBitVideo = true, decodesMain10 = true))
        assertNull(downlinkDepthFor(tenBitVideo = true, decodesMain10 = false))
        assertNull(downlinkDepthFor(tenBitVideo = false, decodesMain10 = false))
    }

    private fun capabilities(highBitDepth: Boolean) = Capabilities(
        protocolVersion = 1, serverName = "test", models = emptyList(), qualityTiers = emptyList(),
        qualityOptions = emptyList(), hasLibrary = false, resizeAlgorithms = emptyList(),
        defaultResizeAlgorithm = "lanczos", highBitDepth = highBitDepth,
    )

    private fun session(color: VideoColor? = VideoColor(1, 1, 1), mapping: DownlinkMapping? = null) = SessionInfo(
        sessionId = "s", mediaPort = 1, uplinkToken = null, downlinkToken = "t", downlinkCodec = "hevc",
        downlinkWidth = 1, downlinkHeight = 1, downlinkContainer = "matroska", epoch = 0, durationSeconds = null,
        timeBase = null, averageRate = null, fitMode = "fit", resizeAlgorithm = null,
        downlinkColor = color, downlinkMapping = mapping,
    )

    private fun state(session: SessionInfo? = session(), highBitDepth: Boolean = true, fallback: Boolean = false) =
        RelayUiState(session = session, capabilities = capabilities(highBitDepth), directLocalFallback = fallback)

    @Test
    fun `the switch restarts only an SDR relay stream it changes`() {
        assertTrue(state().tenBitVideoChangesStream(decodesMain10 = true))
        // HDR, or Dolby Vision mapped to SDR: 10-bit either way.
        assertFalse(state(session(VideoColor(9, 16, 9))).tenBitVideoChangesStream(true))
        val mapped = session(mapping = DownlinkMapping("sdr", null, null, listOf("dolby_vision")))
        assertFalse(state(mapped).tenBitVideoChangesStream(true))
        // A server without deep colour, or a device without a 10-bit decoder, streams 8 bits either way.
        assertFalse(state(highBitDepth = false).tenBitVideoChangesStream(true))
        assertFalse(state().tenBitVideoChangesStream(decodesMain10 = false))
        // Nothing playing, or the original file playing directly.
        assertFalse(state(session = null).tenBitVideoChangesStream(true))
        assertFalse(state(fallback = true).tenBitVideoChangesStream(true))
    }
}
