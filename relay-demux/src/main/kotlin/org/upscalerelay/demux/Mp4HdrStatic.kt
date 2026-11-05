package org.upscalerelay.demux

import org.upscalerelay.protocol.ContentLightLevel
import org.upscalerelay.protocol.MasteringDisplay
import org.upscalerelay.protocol.StaticHdrMetadata
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * HDR10 static metadata of an MP4 (or QuickTime) file's video track, from
 * the mdcv (mastering display colour volume) and clli (content light level)
 * boxes in its sample entry: moov/trak/mdia/minf/stbl/stsd/<entry>.
 *
 * MediaExtractor reports neither (verified on a Galaxy Tab S9 Ultra, Android
 * 16): an MP4 with both gives no hdr-static-info at all. A source whose
 * bitstream carries the SEI loses nothing, since the server copies it and
 * prefers it, but one whose container alone does would.
 *
 * Walks the boxes in place, reading only headers and the few fixed-size
 * fields it needs, so a size is never trusted for an allocation; the sample
 * tables beside the entry are skipped, however large. A box that overruns
 * its parent ends what is trusted of that parent. Best-effort like the
 * Matroska readers: anything unexpected yields null.
 */
object Mp4HdrStatic {
    /**
     * [trackId] is the track MediaExtractor reads (its track-id, the tkhd
     * track_ID); null takes the first video track.
     */
    fun parse(channel: SeekableByteChannel, trackId: Int?): StaticHdrMetadata? = try {
        Mp4Boxes(channel).let { boxes -> boxes.sampleEntry(trackId)?.let(boxes::staticHdr) }
    } catch (_: Exception) {
        null
    }
}

/** A box: its type, and where its payload lies in the file. */
private class Mp4Box(val type: Int, val start: Long, val end: Long) {
    val size: Long get() = end - start
}

private class Mp4Boxes(private val channel: SeekableByteChannel) {
    /** The first sample entry of track [trackId] (else the first video track), when it is video. */
    fun sampleEntry(trackId: Int?): Mp4Box? {
        val top = children(0, channel.size(), MAX_TOP_LEVEL_BOXES)
        val first = top.firstOrNull() ?: return null
        // Not an MP4 (a Matroska file's EBML header reads as an absurd box): stop before walking it.
        if (first.type !in FIRST_BOXES) return null
        val moov = top.firstOrNull { it.type == MOOV } ?: return null
        val trak = children(moov).filter { it.type == TRAK }.firstOrNull { trak ->
            if (trackId != null) trackIdOf(trak) == trackId else handlerOf(trak) == VIDE
        } ?: return null
        if (handlerOf(trak) != VIDE) return null
        val stsd = path(trak, MDIA, MINF, STBL, STSD) ?: return null
        // A full box: version and flags, entry_count, then the entries.
        if (stsd.size < 8) return null
        return children(stsd.start + 8, stsd.end, 1).firstOrNull()
            ?.takeIf { it.size >= VISUAL_SAMPLE_ENTRY_FIELDS }
    }

    /** mdcv and clli among a visual sample entry's child boxes. */
    fun staticHdr(entry: Mp4Box): StaticHdrMetadata? {
        val boxes = children(entry.start + VISUAL_SAMPLE_ENTRY_FIELDS, entry.end, MAX_CHILD_BOXES).toList()
        val mastering = boxes.firstOrNull { it.type == MDCV }?.let(::masteringDisplay)
        val lightLevel = boxes.firstOrNull { it.type == CLLI }?.let(::contentLightLevel)
        return StaticHdrMetadata(mastering, lightLevel).takeIf { mastering != null || lightLevel != null }
    }

    /**
     * Display primaries in the SEI's order (green, blue, red) and its units:
     * chromaticities in 0.00002, luminance in 0.0001 cd/m². Trailing bytes
     * past the 24 are ignored, as FFmpeg does; fewer is no display.
     */
    private fun masteringDisplay(box: Mp4Box): MasteringDisplay? {
        if (box.size < MDCV_BYTES) return null
        val fields = ByteBuffer.wrap(read(box.start, MDCV_BYTES))
        val (green, blue, red, white) = List(4) { fields.uint16() to fields.uint16() }
        return MasteringDisplay(
            primaries = listOf(red, green, blue),
            whitePoint = white,
            maxLuminance = fields.uint32(),
            minLuminance = fields.uint32(),
        ).takeIf { display ->
            // Zero chromaticities are a muxer with nothing to say, as in hdr-static-info.
            (display.primaries + white).none { it.first == 0 || it.second == 0 } && display.valid
        }
    }

    /** MaxCLL and MaxFALL in cd/m²; both zero is unknown. */
    private fun contentLightLevel(box: Mp4Box): ContentLightLevel? {
        if (box.size < CLLI_BYTES) return null
        val fields = ByteBuffer.wrap(read(box.start, CLLI_BYTES))
        return ContentLightLevel(fields.uint16(), fields.uint16()).takeIf { it.maxCll > 0 || it.maxFall > 0 }
    }

    /** tkhd's track_ID: after creation and modification times of four bytes (version 0) or eight. */
    private fun trackIdOf(trak: Mp4Box): Int? {
        val tkhd = children(trak).firstOrNull { it.type == TKHD } ?: return null
        if (tkhd.size < 4) return null
        val offset = when (read(tkhd.start, 1)[0].toInt()) {
            0 -> 12
            1 -> 20
            else -> return null
        }
        if (tkhd.size < offset + 4) return null
        return ByteBuffer.wrap(read(tkhd.start + offset, 4)).int
    }

    /** hdlr's handler_type, after version and flags and pre_defined. */
    private fun handlerOf(trak: Mp4Box): Int? {
        val hdlr = path(trak, MDIA)?.let { mdia -> children(mdia).firstOrNull { it.type == HDLR } } ?: return null
        if (hdlr.size < 12) return null
        return ByteBuffer.wrap(read(hdlr.start + 8, 4)).int
    }

    /** The first [types] child of [parent], then the first of the next type within it, and so on. */
    private fun path(parent: Mp4Box, vararg types: Int): Mp4Box? =
        types.fold<Mp4Box?>(parent) { box, type -> box?.let { children(it).firstOrNull { child -> child.type == type } } }

    private fun children(parent: Mp4Box): Sequence<Mp4Box> = children(parent.start, parent.end, MAX_CHILD_BOXES)

    /**
     * The boxes from [start] to [end], at most [limit] of them. A size of 0
     * runs to [end] (the end of the file, at the top); 1 is a 64-bit size.
     * A box smaller than its header or past [end] ends the list.
     */
    private fun children(start: Long, end: Long, limit: Int): Sequence<Mp4Box> = sequence {
        var position = start
        var count = 0
        while (end - position >= HEADER_BYTES && count++ < limit) {
            val header = ByteBuffer.wrap(read(position, HEADER_BYTES))
            var size = header.uint32()
            val type = header.int
            var headerSize = HEADER_BYTES.toLong()
            when (size) {
                0L -> size = end - position
                1L -> {
                    if (end - position < HEADER_BYTES + 8) return@sequence
                    size = ByteBuffer.wrap(read(position + HEADER_BYTES, 8)).long
                    headerSize += 8
                }
            }
            if (size < headerSize || size > end - position) return@sequence
            yield(Mp4Box(type, position + headerSize, position + size))
            position += size
        }
    }

    private fun read(position: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        channel.position(position)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException()
        }
        return buffer.array()
    }

    private fun ByteBuffer.uint16(): Int = short.toInt() and 0xffff

    private fun ByteBuffer.uint32(): Long = int.toLong() and 0xffff_ffffL

    private companion object {
        fun fourCc(code: String): Int = code.fold(0) { value, char -> (value shl 8) or char.code }

        val MOOV = fourCc("moov")
        val TRAK = fourCc("trak")
        val TKHD = fourCc("tkhd")
        val MDIA = fourCc("mdia")
        val HDLR = fourCc("hdlr")
        val MINF = fourCc("minf")
        val STBL = fourCc("stbl")
        val STSD = fourCc("stsd")
        val MDCV = fourCc("mdcv")
        val CLLI = fourCc("clli")
        val VIDE = fourCc("vide")

        /** What an MP4 or QuickTime file starts with (MediaExtractor's sniffer takes these). */
        val FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot").map(::fourCc).toSet()

        const val HEADER_BYTES = 8

        /**
         * A VisualSampleEntry's fields before its child boxes: SampleEntry's
         * reserved bytes and data_reference_index (8), then 70 of its own.
         * QuickTime's video sample description has the same layout.
         */
        const val VISUAL_SAMPLE_ENTRY_FIELDS = 78
        const val MDCV_BYTES = 24
        const val CLLI_BYTES = 4

        // Bounds on the walk. A fragmented file's moof/mdat pairs follow its
        // moov, so the top level stays short in any file worth reading.
        const val MAX_TOP_LEVEL_BOXES = 4096
        const val MAX_CHILD_BOXES = 1024
    }
}
