package org.upscalerelay.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

