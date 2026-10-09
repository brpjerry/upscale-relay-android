package org.upscalerelay.demux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Builds Matroska bytes in memory (EbmlTestBuilders); no real files needed. */
class MatroskaChaptersTest {
    @Test
    fun `chapters ahead of the clusters are parsed with titles`() {
        val chapters = chaptersElement(
            edition(
                default = false,
                atom(startNs = 0, title = "Wrong edition"),
            ),
            edition(
                default = true,
                atom(startNs = 95_500_000_000, title = null, endNs = 180_000_000_000),
                atom(startNs = 0, title = "Opening"),
                atom(startNs = 40_000_000_000, title = "Hidden", hidden = true),
            ),
        )
        val file = mkv(segmentChildren = chapters + cluster())
        val parsed = MatroskaChapters.parse(memoryChannel(file))
        assertEquals(2, parsed.size)
        assertEquals("Opening", parsed[0].title)
        assertEquals(0.0, parsed[0].startSeconds, 1e-9)
        assertEquals(95.5, parsed[1].startSeconds, 1e-9)
        assertEquals(180.0, parsed[1].endSeconds!!, 1e-9)
        assertEquals(null, parsed[1].title)
    }

    @Test
    fun `chapters behind the clusters are found through the seek head`() {
        val chapters = chaptersElement(edition(default = true, atom(0, "Only")))
        val clusterBytes = cluster()
        // Segment children: [SeekHead][Cluster][Chapters]; the SeekHead's
        // position is relative to the first byte after the Segment size field.
        // The position field is a fixed 8 bytes, so the SeekHead's own length
        // does not depend on the value it carries.
        val probe = seekHeadPointingAt(0L)
        val position = (probe.size + clusterBytes.size).toLong()
        val seekHead = seekHeadPointingAt(position)
        assertEquals(probe.size, seekHead.size)
        val file = mkv(segmentChildren = seekHead + clusterBytes + chapters)
        val parsed = MatroskaChapters.parse(memoryChannel(file))
        assertEquals(1, parsed.size)
        assertEquals("Only", parsed[0].title)
    }

    @Test
    fun `non matroska data and chapterless files yield nothing`() {
        assertTrue(MatroskaChapters.parse(memoryChannel(ByteArray(64))).isEmpty())
        assertTrue(
            MatroskaChapters.parse(memoryChannel(byteArrayOf(1, 2, 3))).isEmpty(),
        )
        val noChapters = mkv(segmentChildren = cluster())
        assertTrue(MatroskaChapters.parse(memoryChannel(noChapters)).isEmpty())
    }

    // -- EBML builders -------------------------------------------------------

    private fun chaptersElement(vararg editions: ByteArray): ByteArray =
        element(0x1043A770, editions.fold(ByteArray(0), ByteArray::plus))

    private fun edition(default: Boolean, vararg atoms: ByteArray): ByteArray {
        val flag = element(0x45DB, byteArrayOf(if (default) 1 else 0))
        return element(0x45B9, flag + atoms.fold(ByteArray(0), ByteArray::plus))
    }

    private fun atom(
        startNs: Long,
        title: String?,
        endNs: Long? = null,
        hidden: Boolean = false,
    ): ByteArray {
        var payload = element(0x91, uint(startNs))
        endNs?.let { payload = payload + element(0x92, uint(it)) }
        if (hidden) payload = payload + element(0x98, byteArrayOf(1))
        title?.let {
            payload = payload + element(0x80, element(0x85, it.toByteArray(Charsets.UTF_8)))
        }
        return element(0xB6, payload)
    }

    private fun seekHeadPointingAt(chaptersPosition: Long): ByteArray {
        val chaptersId = byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70)
        val seek = element(0x53AB, chaptersId) +
            element(0x53AC, ByteBuffer.allocate(8).putLong(chaptersPosition).array())
        return element(0x114D9B74, element(0x4DBB, seek))
    }
}
