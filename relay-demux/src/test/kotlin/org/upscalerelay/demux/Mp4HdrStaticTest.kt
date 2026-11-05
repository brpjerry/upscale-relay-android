package org.upscalerelay.demux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.upscalerelay.protocol.ContentLightLevel
import org.upscalerelay.protocol.MasteringDisplay
import org.upscalerelay.protocol.StaticHdrMetadata
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Synthetic MP4 box layouts for [Mp4HdrStatic]. */
class Mp4HdrStaticTest {
    // hdr10-container.mp4's boxes, byte for byte (ffmpeg's remux of hdr10-container.mkv).
    private val fixtureMdcv = hex("33c286c41d4c0bb884d03e803d13404205f5e10000000032")
    private val fixtureClli = hex("27100190")

    private val fixtureHdr = StaticHdrMetadata(
        MasteringDisplay(
            primaries = listOf(34_000 to 16_000, 13_250 to 34_500, 7_500 to 3_000),
            whitePoint = 15_635 to 16_450,
            maxLuminance = 100_000_000,
            minLuminance = 50,
        ),
        ContentLightLevel(10_000, 400),
    )

    @Test
    fun `mdcv and clli in the video sample entry, primaries reordered from the SEI's green blue red`() {
        val entry = sampleEntry(box("hvcC", ByteArray(40)), box("colr", ByteArray(11)), box("clli", fixtureClli),
            box("mdcv", fixtureMdcv), box("btrt", ByteArray(12)))
        // moov after mdat, as ffmpeg writes without faststart.
        assertEquals(fixtureHdr, parse(mp4(mdat(), moov(videoTrak(1, entry)))))
        assertEquals(fixtureHdr, parse(mp4(moov(videoTrak(1, entry)), mdat()), trackId = 1))
    }

    @Test
    fun `either half alone`() {
        assertEquals(
            StaticHdrMetadata(contentLightLevel = ContentLightLevel(10_000, 400)),
            parse(mp4(moov(videoTrak(1, sampleEntry(box("clli", fixtureClli)))))),
        )
        assertEquals(
            StaticHdrMetadata(masteringDisplay = fixtureHdr.masteringDisplay),
            parse(mp4(moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv)))))),
        )
        assertNull(parse(mp4(moov(videoTrak(1, sampleEntry(box("hvcC", ByteArray(40))))))))
    }

    @Test
    fun `the track read is found by its id, and only a video track is read`() {
        val withHdr = videoTrak(2, sampleEntry(box("mdcv", fixtureMdcv), box("clli", fixtureClli)))
        val file = mp4(moov(audioTrak(1), withHdr, videoTrak(3, sampleEntry())))
        assertEquals(fixtureHdr, parse(file, trackId = 2))
        assertNull(parse(file, trackId = 3))
        // Without an id, the first video track: not the audio one before it.
        assertEquals(fixtureHdr, parse(file, trackId = null))
        // An id that names the audio track, or no track, reads nothing.
        assertNull(parse(file, trackId = 1))
        assertNull(parse(file, trackId = 9))
        // An audio track carrying the boxes is still not video.
        val audioWithBoxes = trak(1, "soun", sampleEntry(box("mdcv", fixtureMdcv)))
        assertNull(parse(mp4(moov(audioWithBoxes)), trackId = 1))
        assertNull(parse(mp4(moov(audioWithBoxes)), trackId = null))
    }

    @Test
    fun `a version 1 tkhd and 64-bit box sizes`() {
        val entry = sampleEntry(box("mdcv", fixtureMdcv), box("clli", fixtureClli))
        val trak = trak(7, "vide", entry, tkhdVersion = 1)
        assertEquals(fixtureHdr, parse(mp4(largeBox("moov", box("mvhd", ByteArray(100)) + trak)), trackId = 7))
    }

    @Test
    fun `a short box is no metadata, and a long one is read for its first fields`() {
        assertEquals(
            StaticHdrMetadata(contentLightLevel = ContentLightLevel(10_000, 400)),
            parse(mp4(moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv.copyOf(20)), box("clli", fixtureClli)))))),
        )
        assertEquals(
            StaticHdrMetadata(masteringDisplay = fixtureHdr.masteringDisplay),
            parse(mp4(moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv), box("clli", fixtureClli.copyOf(3))))))),
        )
        assertEquals(
            fixtureHdr,
            parse(mp4(moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv + ByteArray(8)), box("clli", fixtureClli + byteArrayOf(1))))))),
        )
    }

    @Test
    fun `a box that claims more than its parent holds ends what is read of the parent`() {
        // clli first, then an mdcv declaring 4 GB inside a small sample entry.
        val overrun = box("clli", fixtureClli) + header(0xffff_fff0L, "mdcv") + fixtureMdcv
        assertEquals(
            StaticHdrMetadata(contentLightLevel = ContentLightLevel(10_000, 400)),
            parse(mp4(moov(videoTrak(1, sampleEntryOf(overrun))))),
        )
        // A sample entry larger than its stsd.
        val entry = sampleEntry(box("mdcv", fixtureMdcv))
        val stretched = entry.copyOf().also { ByteBuffer.wrap(it).putInt(0, entry.size + 100) }
        assertNull(parse(mp4(moov(videoTrak(1, rawEntry = stretched)))))
        // A moov past the end of the file, and a 64-bit mdat that would be.
        val moov = moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv))))
        assertNull(parse(mp4(moov.copyOf().also { ByteBuffer.wrap(it).putInt(0, moov.size + 1) })))
        val hugeMdat = header(1, "mdat") + ByteBuffer.allocate(8).putLong(1L shl 62).array()
        assertNull(parse(mp4(hugeMdat, moov)))
        assertNull(parse(mp4(header(1, "mdat") + ByteBuffer.allocate(8).putLong(-1).array(), moov)))
        // A size smaller than the header.
        assertNull(parse(mp4(header(4, "free"), moov)))
    }

    @Test
    fun `a QuickTime movie, with mhlr in mdia and a data handler in minf`() {
        val entry = sampleEntry(box("mdcv", fixtureMdcv), box("clli", fixtureClli))
        val tkhd = box("tkhd", ByteArray(4 + 8 + 4 + 72).also { ByteBuffer.wrap(it).putInt(12, 1) })
        fun hdlr(component: String, subtype: String) =
            box("hdlr", ByteArray(4) + component.toByteArray() + subtype.toByteArray() + ByteArray(12) + byteArrayOf(0))
        val stbl = box("stbl", box("stsd", ByteBuffer.allocate(8).putInt(0).putInt(1).array() + entry))
        val minf = box("minf", box("vmhd", ByteArray(12)) + hdlr("dhlr", "alis") + box("dinf", ByteArray(0)) + stbl)
        val trak = box("trak", tkhd + box("mdia", box("mdhd", ByteArray(24)) + hdlr("mhlr", "vide") + minf))
        val movie = box("wide", ByteArray(0)) + mdat() + box("moov", box("mvhd", ByteArray(100)) + trak)
        assertEquals(fixtureHdr, parse(movie))
        assertEquals(fixtureHdr, parse(movie, trackId = null))
    }

    @Test
    fun `a size of zero runs to the end of its parent`() {
        // The last child of the sample entry, and the file's last box.
        val entry = sampleEntryOf(box("clli", fixtureClli) + header(0, "mdcv") + fixtureMdcv)
        assertEquals(fixtureHdr, parse(mp4(moov(videoTrak(1, entry)), header(0, "mdat") + ByteArray(64))))
        // A box before the moov that runs to the end swallows it.
        val moov = moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv))))
        assertNull(parse(mp4(header(0, "mdat") + ByteArray(64), moov)))
    }

    @Test
    fun `a moov behind media data larger than 4 GiB`() {
        // A long film without faststart: ftyp, a 64-bit mdat, then the moov.
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(4))
        val mdatSize = 5L shl 30
        val mdat = header(1, "mdat") + ByteBuffer.allocate(8).putLong(mdatSize).array()
        val moov = moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv), box("clli", fixtureClli))))
        val moovAt = ftyp.size + mdatSize
        val file = sparseChannel(moovAt + moov.size, mapOf(0L to ftyp + mdat, moovAt to moov))
        assertEquals(fixtureHdr, Mp4HdrStatic.parse(file, 1))
        // The same mdat a byte too long for the file.
        val tooLong = header(1, "mdat") + ByteBuffer.allocate(8).putLong(mdatSize + moov.size + 1).array()
        assertNull(Mp4HdrStatic.parse(sparseChannel(moovAt + moov.size, mapOf(0L to ftyp + tooLong, moovAt to moov)), 1))
    }

    @Test
    fun `a file cut anywhere reads nothing it cannot see, and never throws`() {
        val file = mp4(moov(videoTrak(1, sampleEntry(box("hvcC", ByteArray(40)), box("mdcv", fixtureMdcv), box("clli", fixtureClli)))))
        assertEquals(fixtureHdr, parse(file))
        for (length in 0 until file.size) assertNull("cut at $length", parse(file.copyOf(length)))
    }

    @Test
    fun `implausible values are dropped`() {
        fun mastering(mdcv: ByteArray) = parse(mp4(moov(videoTrak(1, sampleEntry(box("mdcv", mdcv))))))
        assertNull(mastering(ByteArray(24)))
        // Zero chromaticity (green x).
        assertNull(mastering(fixtureMdcv.copyOf().also { it[0] = 0; it[1] = 0 }))
        // A chromaticity past 1.0 (50000).
        assertNull(mastering(fixtureMdcv.copyOf().also { ByteBuffer.wrap(it).putShort(4, 50_001.toShort()) }))
        // The minimum at or above the peak.
        assertNull(mastering(fixtureMdcv.copyOf().also { ByteBuffer.wrap(it).putInt(20, 100_000_000) }))
        // A peak above 2^31 is still a uint32 the SEI can carry.
        val bright = fixtureMdcv.copyOf().also { ByteBuffer.wrap(it).putInt(16, 0xf000_0000L.toInt()) }
        assertEquals(0xf000_0000L, mastering(bright)?.masteringDisplay?.maxLuminance)
        assertNull(parse(mp4(moov(videoTrak(1, sampleEntry(box("clli", ByteArray(4))))))))
    }

    @Test
    fun `a sample entry too short for its own fields, or an empty stsd`() {
        assertNull(parse(mp4(moov(videoTrak(1, rawEntry = box("hvc1", ByteArray(40) + box("mdcv", fixtureMdcv)))))))
        assertNull(parse(mp4(moov(trak(1, "vide", stsd = box("stsd", ByteArray(4)))))))
        assertNull(parse(mp4(moov(trak(1, "vide", stsd = box("stsd", ByteArray(8)))))))
    }

    @Test
    fun `not an MP4`() {
        assertNull(parse(ByteArray(0)))
        assertNull(parse(ByteArray(64)))
        assertNull(parse(mkv(element(0x1654AE6B, ByteArray(8)))))
        // A file whose first box is not one an MP4 starts with.
        assertNull(parse(box("abcd", ByteArray(8)) + moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv))))))
        // Too many boxes before the moov: the walk is bounded.
        val padding = ByteBuffer.allocate(5_000 * 8).apply { repeat(5_000) { putInt(8).put("free".toByteArray()) } }.array()
        assertNull(parse(box("ftyp", ByteArray(8)) + padding + moov(videoTrak(1, sampleEntry(box("mdcv", fixtureMdcv))))))
    }

    private fun parse(file: ByteArray, trackId: Int? = 1): StaticHdrMetadata? = Mp4HdrStatic.parse(memoryChannel(file), trackId)

    private fun mp4(vararg boxes: ByteArray): ByteArray =
        boxes.fold(box("ftyp", "isom".toByteArray() + ByteArray(4) + "isomiso2".toByteArray())) { file, next -> file + next }

    private fun mdat(): ByteArray = box("mdat", ByteArray(64) { it.toByte() })

    private fun moov(vararg traks: ByteArray): ByteArray =
        box("moov", traks.fold(box("mvhd", ByteArray(100))) { payload, trak -> payload + trak })

    private fun videoTrak(id: Int, entry: ByteArray = sampleEntry(), rawEntry: ByteArray = entry): ByteArray =
        trak(id, "vide", rawEntry)

    private fun audioTrak(id: Int): ByteArray = trak(id, "soun", box("mp4a", ByteArray(28)))

    /** tkhd, edts, then mdia/hdlr and mdia/minf/stbl/stsd with one entry, and the sample tables after it. */
    private fun trak(
        id: Int,
        handler: String,
        entry: ByteArray? = null,
        tkhdVersion: Int = 0,
        stsd: ByteArray = box("stsd", ByteBuffer.allocate(8).putInt(0).putInt(1).array() + entry!!),
    ): ByteArray {
        val times = if (tkhdVersion == 1) 16 else 8
        val tkhd = box("tkhd", ByteArray(4 + times + 4 + 72).also { ByteBuffer.wrap(it).put(0, tkhdVersion.toByte()).putInt(4 + times, id) })
        val hdlr = box("hdlr", ByteArray(8) + handler.toByteArray() + ByteArray(12) + "Handler\u0000".toByteArray())
        val stbl = box("stbl", stsd + box("stts", ByteArray(16)) + box("stsz", ByteArray(4000)))
        val minf = box("minf", box("vmhd", ByteArray(12)) + box("dinf", box("dref", ByteArray(20))) + stbl)
        val mdia = box("mdia", box("mdhd", ByteArray(24)) + hdlr + minf)
        return box("trak", tkhd + box("edts", box("elst", ByteArray(20))) + mdia)
    }

    /** An hvc1 VisualSampleEntry: its 78 bytes of fields, then [children]. */
    private fun sampleEntry(vararg children: ByteArray): ByteArray =
        sampleEntryOf(children.fold(ByteArray(0)) { bytes, child -> bytes + child })

    private fun sampleEntryOf(children: ByteArray): ByteArray = box("hvc1", ByteArray(78) + children)

    private fun box(type: String, payload: ByteArray): ByteArray = header(8L + payload.size, type) + payload

    private fun largeBox(type: String, payload: ByteArray): ByteArray =
        header(1, type) + ByteBuffer.allocate(8).putLong(16L + payload.size).array() + payload

    private fun header(size: Long, type: String): ByteArray =
        ByteBuffer.allocate(8).putInt(size.toInt()).put(type.toByteArray()).array()

    /** A file of [size] bytes holding [parts] at their offsets and zeros elsewhere. */
    private fun sparseChannel(size: Long, parts: Map<Long, ByteArray>): SeekableByteChannel =
        object : SeekableByteChannel {
            private var position = 0L

            override fun isOpen(): Boolean = true

            override fun close() = Unit

            override fun read(dst: ByteBuffer): Int {
                if (position >= size) return -1
                val count = minOf(dst.remaining().toLong(), size - position).toInt()
                repeat(count) {
                    val at = position + it
                    val part = parts.entries.firstOrNull { (offset, bytes) -> at >= offset && at < offset + bytes.size }
                    dst.put(part?.let { (offset, bytes) -> bytes[(at - offset).toInt()] } ?: 0)
                }
                position += count
                return count
            }

            override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()

            override fun position(): Long = position

            override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition }

            override fun size(): Long = size

            override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
        }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
