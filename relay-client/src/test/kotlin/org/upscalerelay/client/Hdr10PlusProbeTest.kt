package org.upscalerelay.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.VideoColor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** open_session.video.hdr10plus from the first keyframe (PROTOCOL.md §3.6). */
class Hdr10PlusProbeTest {
    private val hdr10Plus = byteArrayOf(0, 0, 0, 1, 0x4e, 0x01, 0x04, 0x08) +
        byteArrayOf(0xb5.toByte(), 0x00, 0x3c, 0x00, 0x01, 0x04, 0x01, 0x02) + byteArrayOf(0x80.toByte()) +
        byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x10)
    private val plainSlice = byteArrayOf(0, 0, 0, 1, 0x02, 0x01, 0x10)

    private class Source(
        override val videoInfo: UplinkVideoInfo,
        private val units: List<UplinkAccessUnit>,
        private val failure: Exception? = null,
    ) : UplinkMediaSource {
        var opened = 0
        var closedReaders = 0

        override fun openPacketReader(fromPts: Long?): UplinkPacketReader {
            opened++
            failure?.let { throw it }
            val queue = ArrayDeque(units)
            return object : UplinkPacketReader {
                override fun read(): UplinkAccessUnit? = queue.removeFirstOrNull()
                override fun close() { closedReaders++ }
            }
        }

        override fun close() = Unit
    }

    private fun info(codec: String = "hevc", color: VideoColor? = VideoColor(9, 16, 9, "limited")) = UplinkVideoInfo(
        name = "clip.mkv", codec = codec, extradata = null, width = 3840, height = 2160,
        timeBaseNumerator = 1, timeBaseDenominator = 1_000_000,
        averageRateNumerator = null, averageRateDenominator = null, durationSeconds = null, color = color,
    )

    private fun unit(payload: ByteArray, keyframe: Boolean) = UplinkAccessUnit(payload, pts = 0, keyframe = keyframe)

    @Test
    fun `the first keyframe is read, after frames that precede it`() {
        val source = Source(info(), listOf(unit(plainSlice, false), unit(plainSlice, false), unit(hdr10Plus, true)))
        assertTrue(source.firstKeyframeHdr10Plus())
        assertEquals(1, source.closedReaders)
        // Only the first keyframe counts: a later one with HDR10+ is not read.
        assertFalse(Source(info(), listOf(unit(plainSlice, true), unit(hdr10Plus, true))).firstKeyframeHdr10Plus())
    }

    @Test
    fun `a keyframe beyond the scan, a failed read or an empty source is no HDR10+`() {
        val late = List(HDR10PLUS_SCAN_UNITS) { unit(plainSlice, false) } + unit(hdr10Plus, true)
        assertFalse(Source(info(), late).firstKeyframeHdr10Plus())
        assertFalse(Source(info(), emptyList(), failure = IOException("provider gone")).firstKeyframeHdr10Plus())
        assertFalse(Source(info(), emptyList()).firstKeyframeHdr10Plus())
    }

    @Test
    fun `cancelling a probe stuck in a provider read closes the source and returns`() = runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stuck = object : UplinkMediaSource {
            override val videoInfo = info()
            override fun openPacketReader(fromPts: Long?) = object : UplinkPacketReader {
                // A document provider that stops answering until its descriptor closes.
                override fun read(): UplinkAccessUnit? {
                    reading.countDown()
                    closed.await()
                    throw IOException("descriptor closed")
                }
                override fun close() = Unit
            }
            override fun close() = closed.countDown()
        }
        val probe = launch(Dispatchers.Default) { stuck.probeFirstKeyframeHdr10Plus() }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        probe.cancel()
        withTimeout(5_000) { probe.join() }
        assertEquals(0L, closed.count)
    }

    @Test
    fun `only an HEVC source that is PQ or untagged is opened`() {
        val units = listOf(unit(hdr10Plus, true))
        assertTrue(Source(info(color = null), units).firstKeyframeHdr10Plus())
        val hlg = Source(info(color = VideoColor(9, 18, 9)), units)
        assertFalse(hlg.firstKeyframeHdr10Plus())
        val avc = Source(info(codec = "h264"), units)
        assertFalse(avc.firstKeyframeHdr10Plus())
        assertEquals(0, hlg.opened + avc.opened)
    }
}
