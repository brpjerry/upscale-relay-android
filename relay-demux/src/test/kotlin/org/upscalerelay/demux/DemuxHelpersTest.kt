package org.upscalerelay.demux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DemuxHelpersTest {
    @Test fun `length prefixed HEVC access units become Annex B`() {
        val input = byteArrayOf(0, 0, 0, 3, 1, 2, 3, 0, 0, 0, 2, 4, 5)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 1, 2, 3, 0, 0, 0, 1, 4, 5),
            normalizeNalUnits(input, "hevc"),
        )
    }

    @Test fun `existing Annex B and non NAL codecs are unchanged`() {
        val annexB = byteArrayOf(0, 0, 0, 1, 9, 8)
        assertArrayEquals(annexB, normalizeNalUnits(annexB, "h264"))
        val av1 = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(av1, normalizeNalUnits(av1, "av1"))
    }

    @Test fun `byte ranges support bounded open and suffix forms`() {
        assertEquals(ByteRange(0, 99), parseByteRange(null, 100))
        assertEquals(ByteRange(10, 19), parseByteRange("bytes=10-19", 100))
        assertEquals(ByteRange(90, 99), parseByteRange("bytes=-10", 100))
        assertEquals(ByteRange(90, 99), parseByteRange("bytes=90-", 100))
    }
}
