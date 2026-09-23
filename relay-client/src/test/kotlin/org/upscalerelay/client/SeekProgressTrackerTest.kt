package org.upscalerelay.client

import org.junit.Assert.*
import org.junit.Test
import org.upscalerelay.protocol.SeekProgress

class SeekProgressTrackerTest {
    @Test
    fun `advancing subtitle coverage extends seek past one minute but repeated ticks do not`() {
        var now = 0L
        val tracker = SeekProgressTracker { now * 1_000_000 }
        tracker.begin(1)
        repeat(4) { step ->
            now += 50_000
            assertTrue(tracker.accept(progress(1, (step + 1) * 100.0)))
            assertEquals(0L, tracker.idleMillis())
        }
        now += 61_000
        assertTrue(tracker.accept(progress(1, 400.0)))
        assertEquals(61_000L, tracker.idleMillis())
    }

    @Test
    fun `stale regressing unknown and nonfinite progress cannot postpone failure`() {
        var now = 0L
        val tracker = SeekProgressTracker { now * 1_000_000 }
        tracker.begin(2)
        tracker.accept(progress(2, 100.0))
        now = 70_000
        assertFalse(tracker.accept(progress(1, 200.0)))
        tracker.accept(progress(2, 90.0))
        tracker.accept(progress(2, 300.0).copy(stage = "future_stage"))
        tracker.accept(progress(2, Double.POSITIVE_INFINITY))
        tracker.accept(progress(2, null).copy(stage = "video_decode", elapsedSeconds = 70.0))
        assertEquals(70_000L, tracker.idleMillis())
        tracker.clear()
        assertNull(tracker.idleMillis())
        assertFalse(tracker.accept(progress(2, 500.0)))
    }

    @Test
    fun `replacement seek resets its coverage and inactivity clock`() {
        var now = 0L
        val tracker = SeekProgressTracker { now * 1_000_000 }
        tracker.begin(1)
        tracker.accept(progress(1, 500.0))
        now = 100_000
        tracker.begin(2)
        now += 20_000
        tracker.accept(progress(2, 10.0))
        assertEquals(0L, tracker.idleMillis())
    }

    private fun progress(epoch: Int, indexed: Double?) =
        SeekProgress(epoch, 1_000_000, null, 0, null, "subtitle_index", null, indexed)
}
