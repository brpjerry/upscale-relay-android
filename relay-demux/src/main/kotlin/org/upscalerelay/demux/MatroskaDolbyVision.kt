package org.upscalerelay.demux

import org.upscalerelay.protocol.DolbyVisionConfig
import java.nio.channels.SeekableByteChannel

/**
 * The Dolby Vision configuration record of a Matroska file's first video
 * track, from its BlockAdditionMapping (a dvcC, dvvC or dvwC record).
 *
 * MediaExtractor reports a Matroska Dolby Vision track as plain video/hevc
 * with no record (verified on a Galaxy Tab S9 Ultra, Android 16, for a
 * profile 8.1 episode), where an MP4's comes in csd-2. Best-effort like the
 * chapters: anything unexpected yields null.
 */
object MatroskaDolbyVision {
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_BLOCK_ADDITION_MAPPING = 0x41E4L
    private const val ID_BLOCK_ADD_ID_TYPE = 0x41E7L
    private const val ID_BLOCK_ADD_ID_EXTRA_DATA = 0x41EDL

    private const val TRACK_TYPE_VIDEO = 1L
    private const val MAX_TRACKS_PAYLOAD = 4 * 1024 * 1024

    /** The four-character codes of Dolby Vision's records: dvcC, dvvC (profiles 8-10) and dvwC (11 on). */
    private val RECORD_TYPES = setOf("dvcC", "dvvC", "dvwC").map { code ->
        code.fold(0L) { value, char -> (value shl 8) or char.code.toLong() }
    }.toSet()

    fun parse(channel: SeekableByteChannel): DolbyVisionConfig? = try {
        MatroskaSegment.readChild(channel, ID_TRACKS, MAX_TRACKS_PAYLOAD)?.let(::firstVideoRecord)
    } catch (_: Exception) {
        null
    }

    private fun firstVideoRecord(tracks: ByteArray): DolbyVisionConfig? {
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
            var record: DolbyVisionConfig? = null
            while (entry.hasMore) {
                val childId = entry.readElementId()
                val childSize = entry.readElementSize() ?: return null
                when (childId) {
                    ID_TRACK_TYPE -> type = entry.readUnsigned(childSize.toInt())
                    ID_BLOCK_ADDITION_MAPPING -> {
                        // Every mapping is read, so the cursor stays on element
                        // boundaries; the first Dolby Vision one is kept.
                        val mapping = entry.readBytes(childSize.toInt())
                        if (record == null) record = mappingRecord(mapping)
                    }
                    else -> entry.skip(childSize)
                }
            }
            // The first video track is the one MediaExtractor selects.
            if (type == TRACK_TYPE_VIDEO) return record
        }
        return null
    }

    private fun mappingRecord(mapping: ByteArray): DolbyVisionConfig? {
        val cursor = EbmlBytesReader(mapping)
        var type = 0L
        var extraData: ByteArray? = null
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: return null
            when (id) {
                ID_BLOCK_ADD_ID_TYPE -> type = cursor.readUnsigned(size.toInt())
                ID_BLOCK_ADD_ID_EXTRA_DATA -> extraData = cursor.readBytes(size.toInt())
                else -> cursor.skip(size)
            }
        }
        if (type !in RECORD_TYPES) return null
        return extraData?.let(DolbyVisionConfig::fromRecord)
    }
}
