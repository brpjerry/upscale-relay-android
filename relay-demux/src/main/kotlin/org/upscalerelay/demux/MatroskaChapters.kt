package org.upscalerelay.demux

import org.upscalerelay.protocol.ChapterInfo
import java.io.EOFException
import java.nio.ByteBuffer
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
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CLUSTER = 0x1F43B675L
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
    private const val MAX_TOP_LEVEL_ELEMENTS = 4096
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    fun parse(channel: SeekableByteChannel): List<ChapterInfo> = try {
        parseOrThrow(channel)
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseOrThrow(channel: SeekableByteChannel): List<ChapterInfo> {
        val reader = ChannelReader(channel)
        if (reader.readElementId() != ID_EBML) return emptyList()
        reader.skip(reader.readElementSize() ?: return emptyList())
        if (reader.readElementId() != ID_SEGMENT) return emptyList()
        val segmentSize = reader.readElementSize()
        val segmentDataStart = reader.position
        // Streamed files declare an unknown Segment size; a local file ends
        // where the bytes end either way.
        val segmentEnd = if (segmentSize == null) channel.size() else segmentDataStart + segmentSize

        var chaptersSeekPosition: Long? = null
        var elements = 0
        while (reader.position < segmentEnd && elements++ < MAX_TOP_LEVEL_ELEMENTS) {
            val id = reader.readElementId()
            val size = reader.readElementSize() ?: break // unknown-size child: bail
            when (id) {
                ID_CHAPTERS -> return parseChapters(reader.readBytes(checkPayload(size)))
                ID_SEEK_HEAD -> {
                    val payload = reader.readBytes(checkPayload(size))
                    chaptersSeekPosition = chaptersSeekPosition ?: findChaptersSeekPosition(payload)
                }
                ID_CLUSTER -> break // media data; any chapters now live behind the SeekHead entry
                else -> reader.skip(size)
            }
        }
        val position = chaptersSeekPosition ?: return emptyList()
        reader.position = segmentDataStart + position
        if (reader.readElementId() != ID_CHAPTERS) return emptyList()
        val size = reader.readElementSize() ?: return emptyList()
        return parseChapters(reader.readBytes(checkPayload(size)))
    }

    private fun checkPayload(size: Long): Int {
        if (size < 0 || size > MAX_CHAPTERS_PAYLOAD) throw EOFException("element too large: $size")
        return size.toInt()
    }

    private fun findChaptersSeekPosition(seekHead: ByteArray): Long? {
        val cursor = BytesReader(seekHead)
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: return null
            if (id != ID_SEEK) {
                cursor.skip(size)
                continue
            }
            val seek = BytesReader(cursor.readBytes(size.toInt()))
            var targetId = 0L
            var position: Long? = null
            while (seek.hasMore) {
                val childId = seek.readElementId()
                val childSize = seek.readElementSize() ?: return null
                when (childId) {
                    ID_SEEK_ID -> targetId = seek.readUnsigned(childSize.toInt())
                    ID_SEEK_POSITION -> position = seek.readUnsigned(childSize.toInt())
                    else -> seek.skip(childSize)
                }
            }
            if (targetId == ID_CHAPTERS) return position
        }
        return null
    }

    private fun parseChapters(payload: ByteArray): List<ChapterInfo> {
        data class Edition(val default: Boolean, val chapters: List<ChapterInfo>)

        val editions = mutableListOf<Edition>()
        val cursor = BytesReader(payload)
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: break
            if (id != ID_EDITION_ENTRY) {
                cursor.skip(size)
                continue
            }
            val entry = BytesReader(cursor.readBytes(size.toInt()))
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
        val cursor = BytesReader(payload)
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
                    val display = BytesReader(cursor.readBytes(size.toInt()))
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

    /** EBML primitives over a seekable channel. */
    private class ChannelReader(private val channel: SeekableByteChannel) {
        var position: Long
            get() = channel.position()
            set(value) {
                channel.position(value)
            }

        fun readBytes(count: Int): ByteArray {
            if (count < 0) throw EOFException()
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw EOFException()
            }
            return buffer.array()
        }

        fun skip(count: Long) {
            channel.position(channel.position() + count)
        }

        fun readElementId(): Long = readVint(keepMarker = true).first

        /** Element data size; null when the element declares an unknown size. */
        fun readElementSize(): Long? {
            val (value, lengthBits) = readVint(keepMarker = false)
            return if (value == (1L shl lengthBits) - 1) null else value
        }

        private fun readVint(keepMarker: Boolean): Pair<Long, Int> {
            val first = readBytes(1)[0].toInt() and 0xFF
            if (first == 0) throw EOFException("invalid EBML vint")
            val length = Integer.numberOfLeadingZeros(first) - 23 // 1..8
            var value = if (keepMarker) first.toLong() else (first and (0xFF ushr length)).toLong()
            for (byte in readBytes(length - 1)) {
                value = (value shl 8) or (byte.toLong() and 0xFF)
            }
            return value to (7 * length)
        }
    }

    /** Same primitives over an in-memory element payload. */
    private class BytesReader(private val bytes: ByteArray) {
        private var offset = 0

        val hasMore: Boolean get() = offset < bytes.size

        fun readBytes(count: Int): ByteArray {
            if (count < 0 || offset + count > bytes.size) throw EOFException()
            return bytes.copyOfRange(offset, offset + count).also { offset += count }
        }

        fun skip(count: Long) {
            if (count < 0 || offset + count > bytes.size) throw EOFException()
            offset += count.toInt()
        }

        fun readUnsigned(count: Int): Long {
            var value = 0L
            for (byte in readBytes(count)) value = (value shl 8) or (byte.toLong() and 0xFF)
            return value
        }

        fun readElementId(): Long = readVint(keepMarker = true).first

        fun readElementSize(): Long? {
            val (value, lengthBits) = readVint(keepMarker = false)
            return if (value == (1L shl lengthBits) - 1) null else value
        }

        private fun readVint(keepMarker: Boolean): Pair<Long, Int> {
            val first = readBytes(1)[0].toInt() and 0xFF
            if (first == 0) throw EOFException("invalid EBML vint")
            val length = Integer.numberOfLeadingZeros(first) - 23
            var value = if (keepMarker) first.toLong() else (first and (0xFF ushr length)).toLong()
            for (byte in readBytes(length - 1)) {
                value = (value shl 8) or (byte.toLong() and 0xFF)
            }
            return value to (7 * length)
        }
    }
}
