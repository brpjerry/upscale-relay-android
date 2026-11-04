package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderSupportTest {
    @Test
    fun `the tablet's display is asked about at the decoder's padded size`() {
        // 1848 rows: a multiple of 8 but not of 16.
        assertEquals(1856, alignUp(1848, 16))
        assertEquals(1848, alignUp(1848, 8))
        assertEquals(2960, alignUp(2960, 16))
        // No alignment, or one, leaves the size alone.
        assertEquals(1848, alignUp(1848, 1))
        assertEquals(1848, alignUp(1848, 0))
    }
}
