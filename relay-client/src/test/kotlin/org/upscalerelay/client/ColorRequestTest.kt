package org.upscalerelay.client

import org.junit.Assert.assertEquals
import org.junit.Test
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.DisplayTarget
import org.upscalerelay.protocol.HighBitDepth

/** What open_session may ask for, against every pair of server capabilities (§3.5, §3.6). */
class ColorRequestTest {
    private fun server(highBitDepth: Boolean, displayMapping: Boolean) = Capabilities(
        protocolVersion = 1,
        serverName = "test",
        models = emptyList(),
        qualityTiers = listOf("lossless-hevc"),
        qualityOptions = emptyList(),
        hasLibrary = false,
        resizeAlgorithms = listOf("lanczos"),
        defaultResizeAlgorithm = "lanczos",
        highBitDepth = highBitDepth,
        displayMapping = displayMapping,
    )

    private fun negotiate(server: Capabilities, depth: HighBitDepth? = HighBitDepth.HDR) =
        ColorRequest.negotiate(server, depth, DisplayTarget.SDR)

    @Test
    fun `a server with both gets deep colour and the display`() {
        assertEquals(
            ColorRequest(HighBitDepth.HDR, DisplayTarget.SDR),
            negotiate(server(highBitDepth = true, displayMapping = true)),
        )
    }

    @Test
    fun `a display goes only with deep colour`() {
        // Deep colour without mapping: the display is not the server's business.
        assertEquals(
            ColorRequest(HighBitDepth.HDR, null),
            negotiate(server(highBitDepth = true, displayMapping = false)),
        )
        // Mapping advertised without deep colour cannot be asked for: the
        // server maps only a session that asked for deep colour.
        assertEquals(ColorRequest(null, null), negotiate(server(highBitDepth = false, displayMapping = true)))
        // Nor when the client asks for no deep colour (a device without a 10-bit decoder).
        assertEquals(ColorRequest(null, null), negotiate(server(highBitDepth = true, displayMapping = true), depth = null))
    }

    @Test
    fun `a server before either is asked for nothing`() {
        assertEquals(ColorRequest(null, null), negotiate(server(highBitDepth = false, displayMapping = false)))
    }
}
