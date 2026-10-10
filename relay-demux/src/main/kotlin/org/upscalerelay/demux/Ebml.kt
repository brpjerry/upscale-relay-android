package org.upscalerelay.demux

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Bounded EBML access to the Matroska metadata MediaExtractor does not
 * expose. Anything unexpected — non-Matroska bytes, truncated elements,
 * absurd sizes — throws, and callers treat that as "not present".
 */
internal object MatroskaSegment {
    const val ID_EBML = 0x1A45DFA3L
    const val ID_SEGMENT = 0x18538067L
    const val ID_SEEK_HEAD = 0x114D9B74L
    const val ID_SEEK = 0x4DBBL
    const val ID_SEEK_ID = 0x53ABL
    const val ID_SEEK_POSITION = 0x53ACL
    const val ID_CLUSTER = 0x1F43B675L

    private const val MAX_TOP_LEVEL_ELEMENTS = 4096

    /**
     * The payload of the Segment's top-level element [targetId], or null when
     * the file has none. Walks the Segment's children until it or the first
     * Cluster; an element some muxers place behind the clusters is reached
     * through its SeekHead entry instead.
     */
    fun readChild(channel: SeekableByteChannel, targetId: Long, maxPayload: Int): ByteArray? {
        val reader = EbmlChannelReader(channel)
        if (reader.readElementId() != ID_EBML) return null
        reader.skip(reader.readElementSize() ?: return null)
        if (reader.readElementId() != ID_SEGMENT) return null
        val segmentSize = reader.readElementSize()
        val segmentDataStart = reader.position
        // Streamed files declare an unknown Segment size; a local file ends
        // where the bytes end either way.
        val segmentEnd = if (segmentSize == null) channel.size() else segmentDataStart + segmentSize

        var seekPosition: Long? = null
        var elements = 0
        while (reader.position < segmentEnd && elements++ < MAX_TOP_LEVEL_ELEMENTS) {
            val id = reader.readElementId()
            val size = reader.readElementSize() ?: break // unknown-size child: bail
            when (id) {
                targetId -> return reader.readBytes(checkPayload(size, maxPayload))
                ID_SEEK_HEAD -> {
                    val payload = reader.readBytes(checkPayload(size, maxPayload))
                    seekPosition = seekPosition ?: findSeekPosition(payload, targetId)
                }
                ID_CLUSTER -> break // media data; anything later lives behind the SeekHead
                else -> reader.skip(size)
            }
        }
        val position = seekPosition ?: return null
        reader.position = segmentDataStart + position
        if (reader.readElementId() != targetId) return null
        val size = reader.readElementSize() ?: return null
        return reader.readBytes(checkPayload(size, maxPayload))
    }

    private fun checkPayload(size: Long, maxPayload: Int): Int {
        if (size < 0 || size > maxPayload) throw EOFException("element too large: $size")
        return size.toInt()
    }

    private fun findSeekPosition(seekHead: ByteArray, targetId: Long): Long? {
        val cursor = EbmlBytesReader(seekHead)
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: return null
            if (id != ID_SEEK) {
                cursor.skip(size)
                continue
            }
            val seek = EbmlBytesReader(cursor.readBytes(size.toInt()))
            var seekId = 0L
            var position: Long? = null
            while (seek.hasMore) {
                val childId = seek.readElementId()
                val childSize = seek.readElementSize() ?: return null
                when (childId) {
                    ID_SEEK_ID -> seekId = seek.readUnsigned(childSize.toInt())
                    ID_SEEK_POSITION -> position = seek.readUnsigned(childSize.toInt())
                    else -> seek.skip(childSize)
                }
            }
            if (seekId == targetId) return position
        }
        return null
    }
}

/** EBML primitives over a seekable channel. */
internal class EbmlChannelReader(private val channel: SeekableByteChannel) {
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
internal class EbmlBytesReader(private val bytes: ByteArray) {
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
