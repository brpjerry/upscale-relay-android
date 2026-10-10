package org.upscalerelay.demux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemuxHelpersTest {
    @Test fun `malformed NAL length cannot overflow the bounds check`() {
        val input = byteArrayOf(0x7f, -1, -1, -1, 1, 2, 3)
        assertArrayEquals(input, normalizeNalUnits(input, "hevc"))
    }

    @Test fun `malformed and unsatisfiable byte ranges are rejected`() {
        listOf("bytes=0-garbage", "bytes=100-", "bytes=9-2", "bytes=-0", "bytes=0-1,4-5").forEach {
            assertTrue(it, runCatching { parseByteRange(it, 100) }.isFailure)
        }
    }

    @Test fun `HTTP header lines enforce their allocation limit`() {
        assertEquals("Range: bytes=0-", "Range: bytes=0-\r\n".reader().buffered().readBoundedLine())
        assertTrue(runCatching { "x".repeat(8193).reader().buffered().readBoundedLine() }.isFailure)
    }

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

    @Test fun `length prefixed samples whose first length reads as a start code are converted`() {
        // A four-byte length of 256..511 begins 00 00 01, exactly like a
        // three-byte Annex B start code. Only a full parse may decide.
        for (firstLength in listOf(255, 256, 300, 511, 512)) {
            val first = nal(firstLength, header = 0x26)
            val second = nal(2, header = 0x02)
            val third = nal(700, header = 0x40)
            val input = lengthPrefixed(first, second, third)
            assertArrayEquals(
                "first NAL of $firstLength bytes",
                annexB(first, second, third),
                normalizeNalUnits(input, "hevc"),
            )
            assertArrayEquals(
                "first NAL of $firstLength bytes",
                annexB(first, second, third),
                normalizeNalUnits(input, "h264"),
            )
        }
    }

    @Test fun `genuine Annex B with three or four byte start codes is unchanged`() {
        // Realistic HEVC: VPS with a four-byte start code, then a slice with
        // a three-byte one.
        val hevc = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x0c, 0x01, -1, -1) +
            byteArrayOf(0, 0, 1, 0x26, 0x01, -81, 0x11, 0x22)
        assertArrayEquals(hevc, normalizeNalUnits(hevc, "hevc"))
        val leadingThreeByte = byteArrayOf(0, 0, 1, 0x26, 0x01) + ByteArray(300) { 0x5a } +
            byteArrayOf(0, 0, 1, 0x02, 0x01, 0x10)
        assertArrayEquals(leadingThreeByte, normalizeNalUnits(leadingThreeByte, "hevc"))
        // H.264: AUD then IDR slice, both behind four-byte start codes.
        val avc = byteArrayOf(0, 0, 0, 1, 0x09, -16, 0, 0, 0, 1, 0x65, -120, -124, 0x00, 0x10)
        assertArrayEquals(avc, normalizeNalUnits(avc, "h264"))
    }

    @Test fun `malformed length prefixed samples are left alone`() {
        val first = nal(300, header = 0x26)
        val valid = lengthPrefixed(first, nal(2, header = 0x02))
        // Truncated tail, trailing garbage, zero length, and a NAL whose
        // forbidden_zero_bit is set.
        val truncated = valid.copyOf(valid.size - 1)
        assertArrayEquals(truncated, normalizeNalUnits(truncated, "hevc"))
        val trailing = valid + byteArrayOf(7, 7)
        assertArrayEquals(trailing, normalizeNalUnits(trailing, "hevc"))
        val zero = lengthPrefixed(first) + byteArrayOf(0, 0, 0, 0)
        assertArrayEquals(zero, normalizeNalUnits(zero, "hevc"))
        val forbidden = lengthPrefixed(first, nal(4, header = 0x80))
        assertArrayEquals(forbidden, normalizeNalUnits(forbidden, "hevc"))
    }

    private fun nal(size: Int, header: Int): ByteArray =
        ByteArray(size) { index ->
            when (index) {
                0 -> header.toByte()
                1 -> 0x01 // HEVC nuh_temporal_id_plus1 = 1
                else -> (index * 31 + 7).toByte()
            }
        }

    private fun lengthPrefixed(vararg units: ByteArray): ByteArray =
        units.fold(ByteArray(0)) { out, unit ->
            out + byteArrayOf(
                (unit.size ushr 24).toByte(), (unit.size ushr 16).toByte(),
                (unit.size ushr 8).toByte(), unit.size.toByte(),
            ) + unit
        }

    private fun annexB(vararg units: ByteArray): ByteArray =
        units.fold(ByteArray(0)) { out, unit -> out + byteArrayOf(0, 0, 0, 1) + unit }

    @Test fun `byte ranges support bounded open and suffix forms`() {
        assertEquals(ByteRange(0, 99), parseByteRange(null, 100))
        assertEquals(ByteRange(10, 19), parseByteRange("bytes=10-19", 100))
        assertEquals(ByteRange(90, 99), parseByteRange("bytes=-10", 100))
        assertEquals(ByteRange(90, 99), parseByteRange("bytes=90-", 100))
    }
}
