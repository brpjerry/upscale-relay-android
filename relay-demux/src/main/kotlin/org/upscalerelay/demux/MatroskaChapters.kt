package org.upscalerelay.demux

import org.upscalerelay.protocol.ChapterInfo
import java.nio.channels.SeekableByteChannel

/**
 * Minimal EBML walker that extracts Matroska chapter marks.
 *
 * MediaExtractor exposes no chapter metadata, so local-document playback reads
 * the Chapters element straight from the container: walk the Segment's
 * top-level children until Chapters or the first Cluster; if the Chapters
 * element sits behind the clusters (some muxers append it), follow the
 * SeekHead entry instead. Anything unexpected — non-Matroska bytes, truncated
 * elements, absurd sizes — returns an empty list; chapters are best-effort
 * metadata and must never fail playback.
 */
object MatroskaChapters {
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_EDITION_FLAG_DEFAULT = 0x45DBL
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_TIME_END = 0x92L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    private const val MAX_CHAPTERS_PAYLOAD = 4 * 1024 * 1024
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    fun parse(channel: SeekableByteChannel): List<ChapterInfo> = try {
        MatroskaSegment.readChild(channel, ID_CHAPTERS, MAX_CHAPTERS_PAYLOAD)
            ?.let(::parseChapters).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseChapters(payload: ByteArray): List<ChapterInfo> {
        data class Edition(val default: Boolean, val chapters: List<ChapterInfo>)

        val editions = mutableListOf<Edition>()
        val cursor = EbmlBytesReader(payload)
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: break
            if (id != ID_EDITION_ENTRY) {
                cursor.skip(size)
                continue
            }
            val entry = EbmlBytesReader(cursor.readBytes(size.toInt()))
            var default = false
            val chapters = mutableListOf<ChapterInfo>()
            while (entry.hasMore) {
                val childId = entry.readElementId()
                val childSize = entry.readElementSize() ?: break
                when (childId) {
                    ID_EDITION_FLAG_DEFAULT -> default = entry.readUnsigned(childSize.toInt()) != 0L
                    ID_CHAPTER_ATOM -> parseAtom(entry.readBytes(childSize.toInt()))?.let(chapters::add)
                    else -> entry.skip(childSize)
                }
            }
            editions += Edition(default, chapters.sortedBy { it.startSeconds })
        }
        val chosen = editions.firstOrNull { it.default && it.chapters.isNotEmpty() }
            ?: editions.firstOrNull { it.chapters.isNotEmpty() }
        return chosen?.chapters.orEmpty()
    }

    private fun parseAtom(payload: ByteArray): ChapterInfo? {
        val cursor = EbmlBytesReader(payload)
        var startNanos: Long? = null
        var endNanos: Long? = null
        var hidden = false
        var title: String? = null
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: break
            when (id) {
                ID_CHAPTER_TIME_START -> startNanos = cursor.readUnsigned(size.toInt())
                ID_CHAPTER_TIME_END -> endNanos = cursor.readUnsigned(size.toInt())
                ID_CHAPTER_FLAG_HIDDEN -> hidden = cursor.readUnsigned(size.toInt()) != 0L
                ID_CHAPTER_DISPLAY -> {
                    val display = EbmlBytesReader(cursor.readBytes(size.toInt()))
                    while (display.hasMore) {
                        val childId = display.readElementId()
                        val childSize = display.readElementSize() ?: break
                        if (childId == ID_CHAP_STRING && title == null) {
                            title = String(display.readBytes(childSize.toInt()), Charsets.UTF_8)
                                .takeIf { it.isNotBlank() }
                        } else {
                            display.skip(childSize)
                        }
                    }
                }
                else -> cursor.skip(size)
            }
        }
        val start = startNanos ?: return null
        if (hidden || start < 0) return null
        return ChapterInfo(
            startSeconds = start / NANOS_PER_SECOND,
            endSeconds = endNanos?.let { it / NANOS_PER_SECOND },
            title = title,
        )
    }
}
