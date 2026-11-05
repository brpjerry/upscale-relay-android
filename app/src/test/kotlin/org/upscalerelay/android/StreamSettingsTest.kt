package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.client.PASSTHROUGH_MODEL
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.ModelInfo
import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.VideoColor
import org.upscalerelay.protocol.VideoSize

class StreamSettingsTest {
    private val uhd = VideoSize(3840, 2160)
    private val fhd = VideoSize(1920, 1080)
    private val sdr = VideoColor(1, 1, 1)
    private val pq = VideoColor(9, 16, 9)

    private val capabilities = Capabilities(
        protocolVersion = 1, serverName = "test",
        models = listOf(PASSTHROUGH_MODEL, "2x_Model", "2x_Other").map { ModelInfo(it, null) },
        qualityTiers = emptyList(), qualityOptions = emptyList(), hasLibrary = true,
        resizeAlgorithms = emptyList(), defaultResizeAlgorithm = "lanczos", highBitDepth = true,
    )

    private fun session(model: String = "2x_Model", size: VideoSize? = fhd, color: VideoColor = sdr) = SessionInfo(
        sessionId = "s", mediaPort = 1, uplinkToken = null, downlinkToken = "t", downlinkCodec = "hevc",
        downlinkWidth = 1, downlinkHeight = 1, downlinkContainer = "matroska", epoch = 0, durationSeconds = null,
        timeBase = null, averageRate = null, fitMode = "fit", resizeAlgorithm = null,
        model = model, sourceSize = size, downlinkColor = color,
    )

    /** The state a stream opened with [opened] plays in, the settings then changed by [change]. */
    private fun playing(
        session: SessionInfo = session(),
        opened: StreamSettings = settings(),
        change: StreamSettings.() -> StreamSettings = { this },
    ): RelayUiState {
        val now = opened.change()
        return RelayUiState(
            session = session, capabilities = capabilities, selectedModel = now.model,
            qualityTier = now.qualityTier, fitMode = now.fitMode, resizeAlgorithm = now.resizeAlgorithm,
            tenBitVideo = now.tenBitVideo, passthrough4k = now.passthrough4k,
        )
    }

    private fun settings(model: String = "2x_Model", passthrough4k: Boolean = true) = StreamSettings(
        model = model, qualityTier = "hevc-qp18", fitMode = "fit", resizeAlgorithm = "",
        tenBitVideo = false, passthrough4k = passthrough4k,
    )

    private fun RelayUiState.restarts(opened: StreamSettings?, decodesMain10: Boolean = true) =
        settingsRestartNeeded(opened, decodesMain10)

    @Test
    fun `a stream carrying the settings chosen now is left alone`() {
        val opened = settings()
        assertFalse(playing(opened = opened).restarts(opened))
        // Changed and changed back before the open published: nothing to do.
        assertFalse(playing(opened = opened) { copy(qualityTier = "hevc-qp4").copy(qualityTier = "hevc-qp18") }.restarts(opened))
    }

    @Test
    fun `no stream known to carry settings always restarts`() {
        // A restart, reconnect or advance took the stream down and has not
        // published another: a settings restart that was cancelled, or one
        // that failed.
        assertTrue(playing().restarts(opened = null))
    }

    @Test
    fun `quality, framing and resize changes always restart`() {
        val opened = settings()
        assertTrue(playing(opened = opened) { copy(qualityTier = "hevc-qp4") }.restarts(opened))
        assertTrue(playing(opened = opened) { copy(fitMode = "fill") }.restarts(opened))
        assertTrue(playing(opened = opened) { copy(resizeAlgorithm = "bicubic") }.restarts(opened))
    }

    @Test
    fun `a model change restarts unless passthrough holds the 4K source anyway`() {
        val opened = settings()
        assertTrue(playing(opened = opened) { copy(model = "2x_Other") }.restarts(opened))
        val standingIn = session(PASSTHROUGH_MODEL, uhd)
        assertFalse(playing(standingIn, opened) { copy(model = "2x_Other") }.restarts(opened))
        // Choosing passthrough itself restarts, so the player stops calling it a stand-in.
        assertTrue(playing(standingIn, opened) { copy(model = PASSTHROUGH_MODEL) }.restarts(opened))
        // With the option turned off too, the stream leaves passthrough.
        assertTrue(playing(standingIn, opened) { copy(model = "2x_Other", passthrough4k = false) }.restarts(opened))
        // Opened with passthrough chosen: no stand-in to keep, whatever the
        // size, so the stream restarts and is described as one.
        val chosePassthrough = settings(model = PASSTHROUGH_MODEL)
        assertTrue(playing(standingIn, chosePassthrough) { copy(model = "2x_Other") }.restarts(chosePassthrough))
    }

    @Test
    fun `the 10-bit switch restarts only an SDR stream it changes`() {
        val opened = settings()
        assertTrue(playing(opened = opened) { copy(tenBitVideo = true) }.restarts(opened))
        assertFalse(playing(session(color = pq), opened) { copy(tenBitVideo = true) }.restarts(opened))
        assertFalse(playing(opened = opened) { copy(tenBitVideo = true) }.restarts(opened, decodesMain10 = false))
    }

    @Test
    fun `the passthrough option restarts only a stream whose model it changes`() {
        val off = settings(passthrough4k = false)
        assertTrue(playing(session(size = uhd), off) { copy(passthrough4k = true) }.restarts(off))
        assertFalse(playing(session(size = fhd), off) { copy(passthrough4k = true) }.restarts(off))
        val on = settings()
        assertTrue(playing(session(PASSTHROUGH_MODEL, uhd), on) { copy(passthrough4k = false) }.restarts(on))
    }

    @Test
    fun `a model chosen while an open ran survives its publish and restarts it`() {
        // The open read 2x_Model; the user picked 2x_Other before it published.
        val read = settings()
        val published = read.carrying(chosenModel = "2x_Model", qualityTier = "hevc-qp18")
        val selection = settledChoice(current = "2x_Other", read = read.model, resolved = published.model)
        assertEquals("2x_Other", selection)
        assertTrue(playing(opened = published) { copy(model = selection) }.restarts(published))
    }

    @Test
    fun `an unadvertised choice settles on the server's model without a restart`() {
        // Chosen on another server; this one opened its own first model.
        val read = settings(model = "2x_Gone")
        val published = read.carrying(chosenModel = "2x_Model", qualityTier = "hevc-qp4")
        assertEquals("2x_Model", settledChoice(current = "2x_Gone", read = "2x_Gone", resolved = "2x_Model"))
        assertEquals("hevc-qp4", settledChoice(current = "hevc-qp18", read = "hevc-qp18", resolved = "hevc-qp4"))
        assertFalse(playing(opened = published) { copy(model = "2x_Model", qualityTier = "hevc-qp4") }.restarts(published))
    }

    @Test
    fun `an advance decides against the file it published, not the one it left`() {
        // The option turned on during an advance from a 4K file to a 1080p
        // one that read it off: the 1080p file plays its model either way.
        val off = settings(passthrough4k = false)
        assertFalse(playing(session(size = fhd), off) { copy(passthrough4k = true) }.restarts(off))
        // The other way round, the 4K file needs the restart.
        assertTrue(playing(session(size = uhd), off) { copy(passthrough4k = true) }.restarts(off))
    }
}
