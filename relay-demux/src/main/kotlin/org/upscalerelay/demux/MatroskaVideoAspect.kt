package org.upscalerelay.demux

import java.nio.channels.SeekableByteChannel

/**
 * The pixel (sample) aspect ratio of a Matroska file's first video track,
 * from its Video element's pixel and display sizes, the way FFmpeg and mpv
 * read it.
 *
 * MediaExtractor reports sar-width/sar-height for MP4 but not for Matroska
 * (verified on a Galaxy Tab S9 Ultra, Android 16), and anamorphic sources
 * in MKV are the common case. Best-effort: anything unexpected yields null,
 * which means square pixels.
 */
object MatroskaVideoAspect {
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_DISPLAY_WIDTH = 0x54B0L
    private const val ID_DISPLAY_HEIGHT = 0x54BAL
    private const val ID_DISPLAY_UNIT = 0x54B2L

    private const val TRACK_TYPE_VIDEO = 1L

    // Pixels, centimetres, inches and "display aspect ratio" all give the
    // display shape; unit 4 ("unknown") does not.
    private const val MAX_PROPORTIONAL_UNIT = 3L
    private const val MAX_TRACKS_PAYLOAD = 4 * 1024 * 1024
    private const val MAX_DIMENSION = 65_535L

    /** Reduced [numerator, denominator]; null when square, absent or implausible. */
    fun parse(channel: SeekableByteChannel): Pair<Int, Int>? = try {
        MatroskaSegment.readChild(channel, ID_TRACKS, MAX_TRACKS_PAYLOAD)?.let(::firstVideoAspect)
    } catch (_: Exception) {
        null
    }

    private fun firstVideoAspect(tracks: ByteArray): Pair<Int, Int>? {
        val cursor = EbmlBytesReader(tracks)
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: return null
            if (id != ID_TRACK_ENTRY) {
                cursor.skip(size)
                continue
            }
            val entry = EbmlBytesReader(cursor.readBytes(size.toInt()))
            var type = 0L
            var video: ByteArray? = null
            while (entry.hasMore) {
                val childId = entry.readElementId()
                val childSize = entry.readElementSize() ?: return null
                when (childId) {
                    ID_TRACK_TYPE -> type = entry.readUnsigned(childSize.toInt())
                    ID_VIDEO -> video = entry.readBytes(childSize.toInt())
                    else -> entry.skip(childSize)
                }
            }
            // The first video track is the one MediaExtractor selects.
            if (type == TRACK_TYPE_VIDEO) return video?.let(::sampleAspect)
        }
        return null
    }

    private fun sampleAspect(video: ByteArray): Pair<Int, Int>? {
        val cursor = EbmlBytesReader(video)
        var pixelWidth = 0L
        var pixelHeight = 0L
        var displayWidth: Long? = null
        var displayHeight: Long? = null
        var displayUnit = 0L
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: return null
            when (id) {
                ID_PIXEL_WIDTH -> pixelWidth = cursor.readUnsigned(size.toInt())
                ID_PIXEL_HEIGHT -> pixelHeight = cursor.readUnsigned(size.toInt())
                ID_DISPLAY_WIDTH -> displayWidth = cursor.readUnsigned(size.toInt())
                ID_DISPLAY_HEIGHT -> displayHeight = cursor.readUnsigned(size.toInt())
                ID_DISPLAY_UNIT -> displayUnit = cursor.readUnsigned(size.toInt())
                else -> cursor.skip(size)
            }
        }
        if (displayUnit > MAX_PROPORTIONAL_UNIT) return null
        val shownWidth = displayWidth ?: return null
        val shownHeight = displayHeight ?: return null
        if (listOf(pixelWidth, pixelHeight, shownWidth, shownHeight).any { it <= 0 || it > MAX_DIMENSION }) {
            return null
        }
        // SAR = display aspect / storage aspect.
        var numerator = shownWidth * pixelHeight
        var denominator = shownHeight * pixelWidth
        val divisor = gcd(numerator, denominator)
        numerator /= divisor
        denominator /= divisor
        if (numerator == denominator) return null
        // A shape this far from square is a broken header, not a source.
        if (numerator > denominator * 16 || denominator > numerator * 16) return null
        if (numerator > Int.MAX_VALUE || denominator > Int.MAX_VALUE) return null
        return numerator.toInt() to denominator.toInt()
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}
