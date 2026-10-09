package org.upscalerelay.demux

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

// Byte-level EBML builders and an in-memory channel for the Matroska tests.

internal fun mkv(segmentChildren: ByteArray): ByteArray =
    element(0x1A45DFA3, ByteArray(0)) + element(0x18538067, segmentChildren)

internal fun cluster(): ByteArray = element(0x1F43B675, ByteArray(16))

internal fun element(id: Long, payload: ByteArray): ByteArray =
    idBytes(id) + sizeBytes(payload.size.toLong()) + payload

internal fun idBytes(id: Long): ByteArray {
    var length = 1
    while (id ushr (8 * length) != 0L) length++
    return ByteArray(length) { index -> (id ushr (8 * (length - 1 - index))).toByte() }
}

/** Four-byte size vint (marker 0x10) — plenty for test payloads. */
internal fun sizeBytes(size: Long): ByteArray {
    require(size < (1L shl 28) - 1)
    return byteArrayOf(
        (0x10 or (size ushr 24).toInt()).toByte(),
        (size ushr 16).toByte(),
        (size ushr 8).toByte(),
        size.toByte(),
    )
}

internal fun uint(value: Long): ByteArray {
    var length = 1
    while (value ushr (8 * length) != 0L) length++
    return ByteArray(length) { index -> (value ushr (8 * (length - 1 - index))).toByte() }
}

internal fun memoryChannel(bytes: ByteArray): SeekableByteChannel =
    object : SeekableByteChannel {
        private var position = 0L
        private var open = true

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }

        override fun read(dst: ByteBuffer): Int {
            if (position >= bytes.size) return -1
            val count = minOf(dst.remaining().toLong(), bytes.size - position).toInt()
            dst.put(bytes, position.toInt(), count)
            position += count
            return count
        }

        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()

        override fun position(): Long = position

        override fun position(newPosition: Long): SeekableByteChannel {
            position = newPosition
            return this
        }

        override fun size(): Long = bytes.size.toLong()

        override fun truncate(size: Long): SeekableByteChannel =
            throw UnsupportedOperationException()
    }
