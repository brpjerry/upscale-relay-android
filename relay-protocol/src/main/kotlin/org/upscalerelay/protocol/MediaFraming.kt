package org.upscalerelay.protocol

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object MediaFraming {
    const val PROTOCOL_VERSION = 1
    const val DIRECTION_UPLINK: Byte = 0x01
    const val DIRECTION_DOWNLINK: Byte = 0x02
    const val TOKEN_LENGTH = 34
    const val HANDSHAKE_LENGTH = 41
    const val HEADER_LENGTH = 25
    const val FLAG_KEYFRAME = 0x01
    const val FLAG_DISCONTINUITY = 0x02
    const val FLAG_END_OF_STREAM = 0x04
    const val NO_TIMESTAMP = Long.MIN_VALUE
    const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

    private val magic = "UPRLY1".toByteArray(StandardCharsets.US_ASCII)

    fun handshake(direction: Byte, token: String): ByteArray {
        require(direction == DIRECTION_UPLINK || direction == DIRECTION_DOWNLINK) {
            "invalid media direction"
        }
        val tokenBytes = token.toByteArray(StandardCharsets.US_ASCII)
        require(tokenBytes.size == TOKEN_LENGTH && token.all { it.digitToIntOrNull(16) != null }) {
            "token must contain exactly 34 hexadecimal characters"
        }
        return magic + byteArrayOf(direction) + tokenBytes
    }

    fun encode(packet: MediaPacket): ByteArray {
        require(packet.payload.size <= MAX_PAYLOAD_BYTES) { "media payload is too large" }
        return ByteBuffer.allocate(HEADER_LENGTH + packet.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(packet.payload.size)
            .put(packet.flags.toByte())
            .putInt(packet.epoch)
            .putLong(packet.pts)
            .putLong(packet.dts)
            .put(packet.payload)
            .array()
    }

    fun decodeHeader(header: ByteArray): MediaHeader {
        require(header.size == HEADER_LENGTH) { "media header must be $HEADER_LENGTH bytes" }
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val payloadSize = data.int
        require(payloadSize in 0..MAX_PAYLOAD_BYTES) { "invalid media payload length: $payloadSize" }
        return MediaHeader(
            payloadSize = payloadSize,
            flags = data.get().toInt() and 0xff,
            epoch = data.int,
            pts = data.long,
            dts = data.long,
        )
    }

    fun read(input: InputStream): MediaPacket {
        val headerBytes = input.readExactly(HEADER_LENGTH)
        val header = decodeHeader(headerBytes)
        return MediaPacket(
            payload = input.readExactly(header.payloadSize),
            flags = header.flags,
            epoch = header.epoch,
            pts = header.pts,
            dts = header.dts,
        )
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(result, offset, length - offset)
            if (count < 0) throw EOFException("stream ended after $offset of $length bytes")
            offset += count
        }
        return result
    }
}

data class MediaHeader(
    val payloadSize: Int,
    val flags: Int,
    val epoch: Int,
    val pts: Long,
    val dts: Long,
)

data class MediaPacket(
    val payload: ByteArray,
    val flags: Int = 0,
    val epoch: Int = 0,
    val pts: Long = MediaFraming.NO_TIMESTAMP,
    val dts: Long = MediaFraming.NO_TIMESTAMP,
) {
    val keyframe: Boolean get() = flags and MediaFraming.FLAG_KEYFRAME != 0
    val discontinuity: Boolean get() = flags and MediaFraming.FLAG_DISCONTINUITY != 0
    val endOfStream: Boolean get() = flags and MediaFraming.FLAG_END_OF_STREAM != 0
}

