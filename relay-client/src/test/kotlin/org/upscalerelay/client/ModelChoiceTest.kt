package org.upscalerelay.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.ModelInfo
import org.upscalerelay.protocol.VideoSize

/** "Use passthrough for 4K or higher resolution videos": the size rule and the model it decides. */
class ModelChoiceTest {
    private val uhd = VideoSize(3840, 2160)
    private val fhd = VideoSize(1920, 1080)

    private fun server(vararg models: String = arrayOf(PASSTHROUGH_MODEL, "2x_Model")) = Capabilities(
        protocolVersion = 1,
        serverName = "test",
        models = models.map { ModelInfo(it, if (it == PASSTHROUGH_MODEL) 1 else 2) },
        qualityTiers = listOf("lossless-hevc"),
        qualityOptions = emptyList(),
        hasLibrary = true,
        resizeAlgorithms = emptyList(),
        defaultResizeAlgorithm = "lanczos",
    )

    @Test
    fun `3840 wide or 2160 tall and up counts, as on the desktop`() {
        val counts = listOf(
            VideoSize(3840, 2160), // UHD
            VideoSize(4096, 2160), // DCI 4K
            VideoSize(3840, 1600), // a scope crop of UHD
            VideoSize(2160, 3840), // portrait UHD
            VideoSize(7680, 4320),
        )
        val doesNot = listOf(VideoSize(3838, 2158), VideoSize(3839, 2159), VideoSize(2560, 1440), fhd)
        counts.forEach { assertTrue("$it", it.is4kOrHigher()) }
        doesNot.forEach { assertFalse("$it", it.is4kOrHigher()) }
    }

    @Test
    fun `a size needs both sides`() {
        assertEquals(uhd, VideoSize.of(3840, 2160))
        assertEquals(null, VideoSize.of(3840, 0))
        assertEquals(null, VideoSize.of(null, 2160))
        assertEquals(null, VideoSize.of(-1, -1))
    }

    @Test
    fun `a 4K source opens with passthrough in place of the chosen model`() {
        val choice = ModelChoice.decide(server(), "2x_Model", passthrough4k = true, sourceSize = uhd)
        assertEquals(ModelChoice("2x_Model", PASSTHROUGH_MODEL, uhd), choice)
        assertTrue(choice.passthroughFor4k)
    }

    @Test
    fun `a smaller source, an unread size and the option off keep the chosen model`() {
        assertEquals("2x_Model", ModelChoice.decide(server(), "2x_Model", true, fhd).sent)
        // A probe that failed: the desktop keeps the chosen model too.
        assertEquals("2x_Model", ModelChoice.decide(server(), "2x_Model", true, null).sent)
        val off = ModelChoice.decide(server(), "2x_Model", false, uhd)
        assertEquals(ModelChoice("2x_Model", "2x_Model", uhd), off)
        assertFalse(off.passthroughFor4k)
    }

    @Test
    fun `passthrough chosen is not a swap, and a server without one gets the chosen model`() {
        val chosen = ModelChoice.decide(server(), PASSTHROUGH_MODEL, true, uhd)
        assertEquals(PASSTHROUGH_MODEL, chosen.sent)
        assertFalse(chosen.passthroughFor4k)
        assertEquals("2x_Model", ModelChoice.decide(server("2x_Model"), "2x_Model", true, uhd).sent)
    }

    @Test
    fun `a library file is probed only when its size can change the model`() {
        assertTrue(ModelChoice.sizeMatters(server(), "2x_Model", passthrough4k = true))
        assertFalse(ModelChoice.sizeMatters(server(), "2x_Model", passthrough4k = false))
        assertFalse(ModelChoice.sizeMatters(server(), PASSTHROUGH_MODEL, passthrough4k = true))
        assertFalse(ModelChoice.sizeMatters(server("2x_Model"), "2x_Model", passthrough4k = true))
    }

    @Test
    fun `the chosen model is the requested one the server advertises, else its first upscaler`() {
        assertEquals("2x_Model", ModelChoice.chosenModel(server(), "2x_Model"))
        assertEquals("2x_Model", ModelChoice.chosenModel(server(), "gone"))
        assertEquals("2x_Model", ModelChoice.chosenModel(server(), null))
        assertEquals(PASSTHROUGH_MODEL, ModelChoice.chosenModel(server(), PASSTHROUGH_MODEL))
    }
}
