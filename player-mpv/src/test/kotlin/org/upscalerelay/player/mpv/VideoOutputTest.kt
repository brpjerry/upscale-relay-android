package org.upscalerelay.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoOutputTest {
    /** What the bundled mpv reads back at start-up, standing in for outputDefaults. */
    private val defaults = mapOf(
        "egl-output-format" to "auto",
        "target-trc" to "auto",
        "target-prim" to "auto",
        "target-peak" to "auto",
        "sub-hdr-peak" to "sdr",
        "image-subs-hdr-peak" to "1000",
    )

    @Test
    fun `PQ output renders 10 bits for the display's peak, subtitles at the same peak`() {
        assertEquals(
            listOf(
                "egl-output-format" to "rgb10_a2",
                "target-trc" to "pq",
                "target-prim" to "bt.2020",
                "target-peak" to "540",
                // The interface is boosted with the picture on the tablet;
                // at 203 nits subtitles looked grey beside the controls.
                "sub-hdr-peak" to "540",
                "image-subs-hdr-peak" to "540",
            ),
            mpvOutputOptions(VideoOutput.Pq(540.0), defaults),
        )
        assertEquals("gpu-next", mpvVideoOutput(VideoOutput.Pq(540.0)))
    }

    @Test
    fun `SDR output restores exactly what mpv started with`() {
        assertEquals(defaults.toList(), mpvOutputOptions(VideoOutput.Sdr, defaults))
        assertEquals(OUTPUT_OPTIONS, mpvOutputOptions(VideoOutput.Sdr, emptyMap()).map { it.first })
        assertEquals("gpu", mpvVideoOutput(VideoOutput.Sdr))
    }

    @Test
    fun `a display with no usable peak leaves the peaks to mpv`() {
        val options = mpvOutputOptions(VideoOutput.Pq(null), defaults).toMap()
        assertEquals("auto", options["target-peak"])
        assertEquals("sdr", options["sub-hdr-peak"])
        assertEquals("1000", options["image-subs-hdr-peak"])
        assertEquals("pq", options["target-trc"])
        assertEquals("auto", mpvOutputOptions(VideoOutput.Pq(5.0), defaults).toMap()["target-peak"])
    }

    @Test
    fun `luminances are what mpv takes`() {
        assertEquals("540", mpvNits(540.0))
        assertEquals("1107", mpvNits(1106.6))
        assertEquals("10000", mpvNits(10_000.0))
        assertNull(mpvNits(9.0))
        assertNull(mpvNits(10_001.0))
        assertNull(mpvNits(Double.NaN))
    }
}
