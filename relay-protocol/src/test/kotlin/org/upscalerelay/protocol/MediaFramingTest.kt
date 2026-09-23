package org.upscalerelay.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaFramingTest {
    @Test
    fun `matches Python generated v1 packet fixture`() {
        val resource = requireNotNull(javaClass.getResource("/golden/media_packet_v1.json"))
        val fixture = Json.parseToJsonElement(resource.readText()).jsonObject
        val encoded = fixture.getValue("encoded_hex").jsonPrimitive.content.hexBytes()
        val packet = MediaFraming.read(ByteArrayInputStream(encoded))

        assertArrayEquals(fixture.getValue("payload_hex").jsonPrimitive.content.hexBytes(), packet.payload)
        assertEquals(3, packet.flags)
        assertEquals(7, packet.epoch)
        assertEquals(123_456_789L, packet.pts)
        assertEquals(-123L, packet.dts)
        assertTrue(packet.keyframe)
        assertTrue(packet.discontinuity)
        assertArrayEquals(encoded, MediaFraming.encode(packet))
    }

    @Test
    fun `downlink handshake is exactly 41 ASCII bytes`() {
        val token = "0123456789abcdef0123456789abcdef01"
        val handshake = MediaFraming.handshake(MediaFraming.DIRECTION_DOWNLINK, token)
        assertEquals(MediaFraming.HANDSHAKE_LENGTH, handshake.size)
        assertEquals("UPRLY1\u0002$token", handshake.toString(Charsets.US_ASCII))
    }

    @Test
    fun `payload length boundary accepts 64 MiB and rejects signed unsigned overflow`() {
        assertEquals(MediaFraming.MAX_PAYLOAD_BYTES,
            MediaFraming.decodeHeader(header(MediaFraming.MAX_PAYLOAD_BYTES)).payloadSize)
        for (size in listOf(MediaFraming.MAX_PAYLOAD_BYTES + 1, Int.MAX_VALUE, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { MediaFraming.decodeHeader(header(size)) }
        }
    }

    @Test
    fun `oversized frame is rejected before any body read`() {
        val header = header(MediaFraming.MAX_PAYLOAD_BYTES + 1)
        val input = object : InputStream() {
            private var position = 0
            override fun read(): Int {
                check(position < header.size) { "body must never be read" }
                return header[position++].toInt() and 0xff
            }
        }
        assertThrows(IllegalArgumentException::class.java) { MediaFraming.read(input) }
    }

    @Test
    fun `fragmented headers and payload preserve signed timestamps and EOS`() {
        val packet = MediaPacket(byteArrayOf(3, 1, 4),
            flags = MediaFraming.FLAG_END_OF_STREAM, epoch = 9, pts = -12, dts = Long.MIN_VALUE)
        val encoded = MediaFraming.encode(packet)
        val input = object : ByteArrayInputStream(encoded) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, length.coerceAtMost(1))
        }
        val decoded = MediaFraming.read(input)
        assertArrayEquals(packet.payload, decoded.payload)
        assertEquals(packet.pts, decoded.pts)
        assertEquals(packet.dts, decoded.dts)
        assertEquals(9, decoded.epoch)
        assertTrue(decoded.endOfStream)
    }

    @Test
    fun `truncated header and payload report EOF`() {
        assertThrows(EOFException::class.java) {
            MediaFraming.read(ByteArrayInputStream(ByteArray(MediaFraming.HEADER_LENGTH - 1)))
        }
        assertThrows(EOFException::class.java) {
            MediaFraming.read(ByteArrayInputStream(header(2) + byteArrayOf(1)))
        }
    }

    @Test
    fun `streaming encoder matches contiguous wire format`() {
        val packet = MediaPacket(byteArrayOf(1, 2, 3), flags = 3, epoch = 2, pts = -1, dts = -4)
        val output = ByteArrayOutputStream()
        MediaFraming.write(output, packet)
        assertArrayEquals(MediaFraming.encode(packet), output.toByteArray())
        assertArrayEquals(packet.payload, MediaFraming.read(ByteArrayInputStream(output.toByteArray())).payload)
    }

    private fun header(size: Int): ByteArray = ByteBuffer.allocate(MediaFraming.HEADER_LENGTH)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(size).array()
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
