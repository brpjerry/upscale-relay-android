package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.client.PASSTHROUGH_MODEL
import org.upscalerelay.client.PlaybackEndpoint
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.ModelInfo
import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.VideoSize

class Passthrough4kTest {
    private val uhd = VideoSize(3840, 2160)
    private val fhd = VideoSize(1920, 1080)

    private fun capabilities(vararg models: String = arrayOf(PASSTHROUGH_MODEL, "2x_Model")) = Capabilities(
        protocolVersion = 1, serverName = "test", models = models.map { ModelInfo(it, null) },
        qualityTiers = emptyList(), qualityOptions = emptyList(), hasLibrary = true,
        resizeAlgorithms = emptyList(), defaultResizeAlgorithm = "lanczos",
    )

    private fun session(model: String, size: VideoSize?) = SessionInfo(
        sessionId = "s", mediaPort = 1, uplinkToken = null, downlinkToken = "t", downlinkCodec = "hevc",
        downlinkWidth = 1, downlinkHeight = 1, downlinkContainer = "matroska", epoch = 0, durationSeconds = null,
        timeBase = null, averageRate = null, fitMode = "fit", resizeAlgorithm = null,
        model = model, sourceSize = size,
    )

    private fun state(
        session: SessionInfo?,
        chosen: String = "2x_Model",
        capabilities: Capabilities = capabilities(),
        fallback: Boolean = false,
    ) = RelayUiState(
        session = session, selectedModel = chosen, capabilities = capabilities, directLocalFallback = fallback,
    )

    @Test
    fun `turning it on restarts a 4K stream, and a library file whose size was never read`() {
        assertTrue(state(session("2x_Model", uhd)).passthrough4kChangesStream(enabled = true))
        // Opened with the option off: the server file was not probed.
        assertTrue(state(session("2x_Model", null)).passthrough4kChangesStream(enabled = true))
        assertFalse(state(session("2x_Model", fhd)).passthrough4kChangesStream(enabled = true))
        // Nothing to swap in, or nothing to swap out.
        assertFalse(state(session("2x_Model", uhd), capabilities = capabilities("2x_Model")).passthrough4kChangesStream(true))
        assertFalse(state(session(PASSTHROUGH_MODEL, uhd), chosen = PASSTHROUGH_MODEL).passthrough4kChangesStream(true))
    }

    @Test
    fun `turning it off restarts only a stream it put on passthrough`() {
        assertTrue(state(session(PASSTHROUGH_MODEL, uhd)).passthrough4kChangesStream(enabled = false))
        assertFalse(state(session("2x_Model", fhd)).passthrough4kChangesStream(enabled = false))
        assertFalse(state(session(PASSTHROUGH_MODEL, uhd), chosen = PASSTHROUGH_MODEL).passthrough4kChangesStream(false))
    }

    @Test
    fun `nothing restarts without a relay stream`() {
        assertFalse(state(session = null).passthrough4kChangesStream(enabled = true))
        assertFalse(state(session("2x_Model", uhd), fallback = true).passthrough4kChangesStream(enabled = true))
        assertFalse(state(session(PASSTHROUGH_MODEL, uhd), fallback = true).passthrough4kChangesStream(enabled = false))
    }

    @Test
    fun `the player says when passthrough stands in for a 4K source`() {
        fun endpoint(sent: String, chosen: String) = PlaybackEndpoint(
            localUrl = "http://127.0.0.1:1/", originalMediaUrl = null, subtitleFontsDirectory = null,
            auxTracks = "external", auxAttachments = "embedded", requestedAuxTracks = null,
            requestedAuxAttachments = null, session = session(sent, uhd), model = sent,
            qualityTier = "lossless-hevc", fitMode = "fit", resizeAlgorithm = null, chosenModel = chosen,
        )
        assertEquals("passthrough (4K source)", endpoint(PASSTHROUGH_MODEL, "2x_Model").modelDescription())
        assertEquals(PASSTHROUGH_MODEL, endpoint(PASSTHROUGH_MODEL, PASSTHROUGH_MODEL).modelDescription())
        assertEquals("2x_Model", endpoint("2x_Model", "2x_Model").modelDescription())
    }
}
