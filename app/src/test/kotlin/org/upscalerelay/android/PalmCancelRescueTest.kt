package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.upscalerelay.android.PalmCancelRescue.Kind.CANCEL
import org.upscalerelay.android.PalmCancelRescue.Kind.DOWN
import org.upscalerelay.android.PalmCancelRescue.Kind.MOVE
import org.upscalerelay.android.PalmCancelRescue.Kind.OTHER
import org.upscalerelay.android.PalmCancelRescue.Kind.UP
import org.upscalerelay.android.PalmCancelRescue.Verdict.FLUSH
import org.upscalerelay.android.PalmCancelRescue.Verdict.HOLD
import org.upscalerelay.android.PalmCancelRescue.Verdict.PASS
import org.upscalerelay.android.PalmCancelRescue.Verdict.RESCUE

/**
 * The event sequences are the ones the Tab S9 Ultra delivered on 2026-10-09,
 * with their own times and positions (a 2960x1848 landscape window at 1.75
 * px/dp, so the 64dp strip is 112 px and 24dp of drift is 42 px).
 */
class PalmCancelRescueTest {
    private val rescue = PalmCancelRescue(stripPx = 112f, driftPx = 42f)

    private class Event(
        val kind: PalmCancelRescue.Kind,
        val time: Long,
        val x: Int,
        val y: Int,
        val canceled: Boolean = false,
        val pointers: Int = 1,
    )

    private fun verdicts(armed: Boolean = true, vararg events: Event) = events.map {
        rescue.onEvent(it.kind, it.time, it.x.toFloat(), it.y.toFloat(), it.pointers, it.canceled, armed)
    }

    @Test
    fun `a press on the lock that the tablet cancelled as the finger lifted is delivered`() {
        assertEquals(
            listOf(PASS, PASS, HOLD, RESCUE),
            verdicts(
                true,
                Event(DOWN, 40664236, 2884, 78),
                Event(MOVE, 40664260, 2884, 78),
                Event(CANCEL, 40664330, 2884, 78, canceled = true),
                Event(UP, 40664344, 2884, 78),
            ),
        )
        // The one that drifted furthest, 10 px.
        assertEquals(
            listOf(PASS, PASS, HOLD, RESCUE),
            verdicts(
                true,
                Event(DOWN, 40677677, 2896, 49),
                Event(MOVE, 40677688, 2896, 49),
                Event(CANCEL, 40677786, 2892, 59, canceled = true),
                Event(UP, 40677802, 2892, 59),
            ),
        )
        // The slowest, 135 ms from the down to the cancel.
        assertEquals(
            listOf(PASS, HOLD, RESCUE),
            verdicts(
                true,
                Event(DOWN, 40678126, 2893, 62),
                Event(CANCEL, 40678261, 2893, 63, canceled = true),
                Event(UP, 40678277, 2893, 63),
            ),
        )
    }

    @Test
    fun `a palm that stays down goes on being cancelled`() {
        // A hand on the edge of the screen: flagged 200 ms in, then a cancel
        // for every sample of the second it stayed, then the up.
        assertEquals(
            listOf(PASS, PASS, HOLD, FLUSH, PASS, PASS, PASS),
            verdicts(
                true,
                Event(DOWN, 40657280, 2884, 78),
                Event(MOVE, 40657338, 2884, 78),
                Event(CANCEL, 40657480, 2884, 78, canceled = true),
                Event(CANCEL, 40657488, 2884, 79, canceled = true),
                Event(CANCEL, 40657505, 2884, 80, canceled = true),
                Event(CANCEL, 40658522, 2884, 80, canceled = true),
                Event(UP, 40658536, 2884, 80),
            ),
        )
    }

    @Test
    fun `the tablet's verdict stands below the top strip and outside the player`() {
        val pressOnThePicture = arrayOf(
            Event(DOWN, 1_000, 1480, 900),
            Event(CANCEL, 1_090, 1480, 900, canceled = true),
            Event(UP, 1_105, 1480, 900),
        )
        assertEquals(listOf(PASS, PASS, PASS), verdicts(true, *pressOnThePicture))
        assertEquals(null, rescue.refusal)
        verdicts(true, pressOnThePicture[0], pressOnThePicture[1])
        assertEquals("below the top strip, at y=900", rescue.refusal)
        val pressOnTheBar = arrayOf(
            Event(DOWN, 2_000, 2884, 78),
            Event(CANCEL, 2_090, 2884, 78, canceled = true),
            Event(UP, 2_105, 2884, 78),
        )
        assertEquals(listOf(PASS, PASS, PASS), verdicts(false, *pressOnTheBar))
    }

    @Test
    fun `only a cancel the system marked, on a press that stayed put and then lifted, counts`() {
        // An ordinary cancel, such as a parent taking the gesture over.
        assertEquals(
            listOf(PASS, PASS, PASS),
            verdicts(true, Event(DOWN, 0, 2884, 78), Event(CANCEL, 90, 2884, 78), Event(UP, 105, 2884, 78)),
        )
        // The up comes too late to have been the same lift.
        assertEquals(
            listOf(PASS, HOLD, FLUSH),
            verdicts(
                true,
                Event(DOWN, 1_000, 2884, 78),
                Event(CANCEL, 1_090, 2884, 78, canceled = true),
                Event(UP, 1_141, 2884, 78),
            ),
        )
        // A swipe along the bar that ended in a palm flag.
        assertEquals(
            listOf(PASS, PASS, PASS, PASS),
            verdicts(
                true,
                Event(DOWN, 2_000, 2700, 78),
                Event(MOVE, 2_050, 2884, 78),
                Event(CANCEL, 2_090, 2884, 78, canceled = true),
                Event(UP, 2_105, 2884, 78),
            ),
        )
        // Something held for longer than a press before it was flagged.
        assertEquals(
            listOf(PASS, PASS, PASS),
            verdicts(
                true,
                Event(DOWN, 3_000, 2884, 78),
                Event(CANCEL, 3_401, 2884, 78, canceled = true),
                Event(UP, 3_415, 2884, 78),
            ),
        )
        // A second finger came down during it.
        assertEquals(
            listOf(PASS, PASS, PASS, PASS),
            verdicts(
                true,
                Event(DOWN, 4_000, 2884, 78),
                Event(OTHER, 4_030, 2884, 78, pointers = 2),
                Event(CANCEL, 4_090, 2884, 78, canceled = true),
                Event(UP, 4_105, 2884, 78),
            ),
        )
    }

    @Test
    fun `a held cancel that was delivered by its timeout does not claim the next press`() {
        assertEquals(
            listOf(PASS, HOLD),
            verdicts(true, Event(DOWN, 0, 2884, 78), Event(CANCEL, 90, 2884, 78, canceled = true)),
        )
        assertEquals(null, rescue.refusal)
        rescue.flushed()
        assertEquals(
            listOf(PASS, PASS),
            verdicts(true, Event(DOWN, 1_000, 2884, 78), Event(UP, 1_100, 2884, 78)),
        )
    }
}
