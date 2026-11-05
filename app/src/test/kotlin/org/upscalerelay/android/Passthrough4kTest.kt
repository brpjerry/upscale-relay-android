package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun capabilities(vararg models: String = arrayOf(PASSTHROUGH_MODEL, "2x_Model", "2x_Other")) = Capabilities(
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
        local: Boolean = false,
        passthrough4k: Boolean = true,
    ) = RelayUiState(
        session = session, selectedModel = chosen, capabilities = capabilities, directLocalFallback = fallback,
        localPlayback = local, passthrough4k = passthrough4k,
    )

    @Test
    fun `turning it on restarts a 4K stream, and a library file whose size was never read`() {
        assertTrue(state(session("2x_Model", uhd)).passthrough4kChangesStream(enabled = true))
        // Opened with the option off: the server file was not probed.
        assertTrue(state(session("2x_Model", null)).passthrough4kChangesStream(enabled = true))
        assertFalse(state(session("2x_Model", fhd)).passthrough4kChangesStream(enabled = true))
        // A local file's size is its demuxer's: unknown stays unknown, so a restart changes nothing.
        assertFalse(state(session("2x_Model", null), local = true).passthrough4kChangesStream(enabled = true))
        assertTrue(state(session("2x_Model", uhd), local = true).passthrough4kChangesStream(enabled = true))
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
    fun `another upscaling model leaves a 4K passthrough stream alone`() {
        val standingIn = state(session(PASSTHROUGH_MODEL, uhd))
        assertTrue(standingIn.modelChangeKeepsStream("2x_Other"))
        assertTrue(state(session(PASSTHROUGH_MODEL, uhd), local = true).modelChangeKeepsStream("2x_Other"))
        // Choosing passthrough itself restarts, so the player stops calling it a stand-in.
        assertFalse(standingIn.modelChangeKeepsStream(PASSTHROUGH_MODEL))
        // From passthrough chosen, the label would go stale: restart.
        assertFalse(state(session(PASSTHROUGH_MODEL, uhd), chosen = PASSTHROUGH_MODEL).modelChangeKeepsStream("2x_Other"))
        // The model is what plays, or the option is off, or the size is not known to be 4K.
        assertFalse(state(session("2x_Model", fhd)).modelChangeKeepsStream("2x_Other"))
        assertFalse(state(session(PASSTHROUGH_MODEL, uhd), passthrough4k = false).modelChangeKeepsStream("2x_Other"))
        assertFalse(state(session(PASSTHROUGH_MODEL, null)).modelChangeKeepsStream("2x_Other"))
        assertFalse(state(session(PASSTHROUGH_MODEL, uhd), fallback = true).modelChangeKeepsStream("2x_Other"))
    }

    @Test
    fun `a restart reuses only the size read for its own file`() {
        val sizes = SourceSizeMemo<String>()
        assertNull(sizes.sizeOf("Show/E01.mkv"))
        sizes.remember("Show/E01.mkv", fhd)
        assertEquals(fhd, sizes.sizeOf("Show/E01.mkv"))
        // Auto-play next: the next file's restart runs while E01's session is on screen.
        assertNull(sizes.sizeOf("Show/E02.mkv"))
        sizes.remember("Show/E02.mkv", uhd)
        assertEquals(uhd, sizes.sizeOf("Show/E02.mkv"))
        assertNull(sizes.sizeOf("Show/E01.mkv"))
        // An open that read nothing (the option off) leaves nothing to reuse.
        sizes.remember("Show/E02.mkv", null)
        assertNull(sizes.sizeOf("Show/E02.mkv"))
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
