package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.upscalerelay.player.mpv.MpvMetrics

class PlaybackPositionTest {
    private fun state(position: Double, seekTarget: Double? = null) = RelayUiState(
        mpvMetrics = MpvMetrics(positionSeconds = position),
        seekTargetSeconds = seekTarget,
    )

    @Test
    fun `settled playback is where mpv says it is`() {
        assertEquals(612.5, state(position = 612.5).userPositionSeconds(), 0.0)
    }

    @Test
    fun `a seek still loading counts as its target, not mpv's reset position`() {
        // Every seek reloads the stream; until it plays, mpv reports zero.
        val loading = state(position = 0.0, seekTarget = 685.0)
        assertEquals(685.0, loading.userPositionSeconds(), 0.0)
        // So "back 1:25" pressed now lands at 10:00, not at the start.
        assertEquals(600.0, (loading.userPositionSeconds() - 85).coerceAtLeast(0.0), 0.0)
    }

    @Test
    fun `the target wins while the new stream is still short of it`() {
        // A stream starts on the keyframe before its target.
        assertEquals(685.0, state(position = 679.0, seekTarget = 685.0).userPositionSeconds(), 0.0)
    }
}
