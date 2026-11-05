package org.upscalerelay.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayPlacementTest {
    /** The tablet's full-screen relay. */
    private val relayWidth = 2960
    private val relayHeight = 1848

    /** A 16:9 picture as mpv draws it in "fit" framing: bars above and below. */
    private val sixteenByNine = intArrayOf(0, 91, 2960, 1756)

    private fun place(picture: IntArray?, viewWidth: Int, viewHeight: Int) =
        relayPlacement(relayWidth, relayHeight, picture, viewWidth, viewHeight)

    /** Where the crop lands in the view: left, top, right, bottom. */
    private fun RelayPlacement.onScreen() = listOf(
        x + left * scale,
        y + top * scale,
        x + right * scale,
        y + bottom * scale,
    )

    private fun assertOnScreen(expected: List<Double>, placement: RelayPlacement) {
        placement.onScreen().zip(expected).forEach { (actual, want) -> assertEquals(want, actual.toDouble(), 0.5) }
    }

    @Test
    fun `full screen shows the whole relay as it is`() {
        for (picture in listOf(null, sixteenByNine, intArrayOf(0, 0, 2960, 1848))) {
            val placement = place(picture, 2960, 1848)
            assertEquals(RelayPlacement(0, 0, 2960, 1848, 1f, 0f, 0f), placement)
        }
    }

    @Test
    fun `PiP in fit framing shows the picture alone, filling the window`() {
        // MainActivity gives the PiP window the downlink's aspect: 16:9 here.
        val placement = place(sixteenByNine, 1024, 576)
        assertEquals(listOf(0, 91, 2960, 1756), listOf(placement.left, placement.top, placement.right, placement.bottom))
        assertOnScreen(listOf(0.0, 0.0, 1024.0, 576.0), placement)
    }

    @Test
    fun `PiP in cover framing shrinks the whole frame`() {
        // The downlink is the relay's size, and so is the window's aspect.
        val placement = place(intArrayOf(0, 0, 2960, 1848), 1018, 635)
        assertEquals(listOf(0, 0, 2960, 1848), listOf(placement.left, placement.top, placement.right, placement.bottom))
        // The window rounds to a slightly different aspect: under a pixel spare.
        assertOnScreen(listOf(0.5, 0.0, 1017.5, 635.0), placement)
    }

    @Test
    fun `before mpv knows the picture, the whole relay is fitted`() {
        val placement = place(null, 1024, 576)
        assertEquals(listOf(0, 0, 2960, 1848), listOf(placement.left, placement.top, placement.right, placement.bottom))
        // Pillarboxed: the relay is 1.6, the window 16:9.
        val (left, top, right, bottom) = placement.onScreen()
        assertEquals(576.0, (bottom - top).toDouble(), 0.5)
        assertEquals(1024.0 - (right - left), 2.0 * left, 0.5)
    }

    @Test
    fun `a window wider than the picture keeps mpv's bars beside it, not the view's`() {
        // A 2.76:1 picture in a PiP window clamped to 2.39:1.
        val scope = intArrayOf(0, 388, 2960, 1460)
        val placement = place(scope, 1195, 500)
        assertEquals(0, placement.left)
        assertEquals(2960, placement.right)
        // Taller than the picture, to reach the window's aspect.
        assertEquals(1238.0, (placement.bottom - placement.top).toDouble(), 1.0)
        assertOnScreen(listOf(0.0, 0.0, 1195.0, 500.0), placement)
    }

    @Test
    fun `a view the relay cannot fill keeps the whole relay, fitted`() {
        // Split screen, square: even the whole relay is wider than the view.
        val placement = place(sixteenByNine, 1000, 1000)
        assertEquals(listOf(0, 0, 2960, 1848), listOf(placement.left, placement.top, placement.right, placement.bottom))
        val (left, top, right, bottom) = placement.onScreen()
        assertEquals(1000.0, (right - left).toDouble(), 0.5)
        assertEquals(1000.0 - (bottom - top), 2.0 * top, 0.5)
    }

    @Test
    fun `a picture mpv places off centre stays whole`() {
        val placement = place(intArrayOf(100, 0, 1700, 900), 1600, 900)
        assertEquals(listOf(100, 0, 1700, 900), listOf(placement.left, placement.top, placement.right, placement.bottom))
        assertOnScreen(listOf(0.0, 0.0, 1600.0, 900.0), placement)
    }
}
