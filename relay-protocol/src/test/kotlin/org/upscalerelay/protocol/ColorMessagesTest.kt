package org.upscalerelay.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deep colour (PROTOCOL.md §3.5) and display mapping (§3.6) as the server writes them. */
class ColorMessagesTest {
    private val base = """{"session_id":"s","media_port":8591,"downlink_token":"t","downlink_codec":"hevc","downlink_width":2960,"downlink_height":1848"""

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun session(extra: String) = SessionInfo.fromJson(json("$base$extra}"))
    private fun capabilities(extra: String) = Capabilities.fromJson(
        json("""{"protocol_version":1,"server_name":"s","models":[],"quality_tiers":[]$extra}"""),
    )

    @Test
    fun `capabilities advertise deep colour and display mapping only as true booleans`() {
        val both = capabilities(""","high_bit_depth":true,"display_mapping":true""")
        assertTrue(both.highBitDepth)
        assertTrue(both.displayMapping)
        val older = capabilities("")
        assertFalse(older.highBitDepth)
        assertFalse(older.displayMapping)
        for (value in listOf("false", "null", "\"true\"", "1", "{}")) {
            val odd = capabilities(""","high_bit_depth":$value,"display_mapping":$value""")
            assertFalse(value, odd.highBitDepth)
            assertFalse(value, odd.displayMapping)
        }
    }

    @Test
    fun `a session from a server before deep colour is an 8-bit stream of unknown colour`() {
        val session = session("")
        assertEquals(8, session.downlinkBitDepth)
        assertNull(session.downlinkColor)
        assertFalse(session.downlinkDolbyVision)
        assertNull(session.sourceBitDepth)
        assertNull(session.downlinkMapping)
    }

    @Test
    fun `an HDR10 session with a Dolby Vision RPU is read as the server reports it`() {
        val session = session(
            ""","downlink_bit_depth":10,"downlink_color":{"primaries":9,"transfer":16,"matrix":9,"range":"limited"},""" +
                """"downlink_dolby_vision":true,"source_bit_depth":10,"downlink_mapping":null""",
        )
        assertEquals(10, session.downlinkBitDepth)
        assertEquals(VideoColor(primaries = 9, transfer = 16, matrix = 9, range = "limited"), session.downlinkColor)
        assertTrue(session.downlinkColor!!.isHdr)
        assertTrue(session.downlinkDolbyVision)
        assertEquals(10, session.sourceBitDepth)
        assertNull(session.downlinkMapping)
    }

    @Test
    fun `a display-mapped session names what the server applied`() {
        val session = session(
            ""","downlink_bit_depth":10,"downlink_color":{"primaries":1,"transfer":1,"matrix":1,"range":"limited"},""" +
                """"downlink_mapping":{"transfer":"sdr","peak_nits":null,"sdr_white_nits":null,"applied":["dolby_vision"]}""",
        )
        assertFalse(session.downlinkColor!!.isHdr)
        assertEquals(DownlinkMapping("sdr", null, null, listOf("dolby_vision")), session.downlinkMapping)
    }

    @Test
    fun `malformed colour fields read as absent instead of failing the session`() {
        val session = session(
            ""","downlink_bit_depth":12,"downlink_color":{"primaries":9,"transfer":"16","matrix":9},""" +
                """"downlink_dolby_vision":"yes","source_bit_depth":"10","downlink_mapping":["sdr"]""",
        )
        assertEquals(8, session.downlinkBitDepth)
        assertNull(session.downlinkColor)
        assertFalse(session.downlinkDolbyVision)
        assertNull(session.sourceBitDepth)
        assertNull(session.downlinkMapping)
        // An unknown range alone does not cost the code points.
        assertEquals(
            VideoColor(9, 18, 9, null),
            session(""","downlink_color":{"primaries":9,"transfer":18,"matrix":9,"range":"studio"}""").downlinkColor,
        )
        assertNull(session(""","downlink_color":{"primaries":9,"transfer":300,"matrix":9}""").downlinkColor)
        // Unknown applied names survive; non-strings do not.
        assertEquals(
            listOf("dolby_vision", "future_metadata"),
            session(""","downlink_mapping":{"transfer":"pq","applied":["dolby_vision",7,"future_metadata"]}""")
                .downlinkMapping!!.applied,
        )
    }

    @Test
    fun `seek_ready describes a mapped epoch and leaves other sessions alone`() {
        val pq = VideoColor(9, 16, 9, "limited")
        val mapped = session("").copy(
            downlinkBitDepth = 10,
            downlinkColor = pq,
            downlinkMapping = DownlinkMapping("pq", null, null, emptyList()),
        )
        val toSdr = mapped.describedBy(json(
            """{"type":"seek_ready","epoch":3,"downlink_color":{"primaries":1,"transfer":1,"matrix":1,"range":"limited"},""" +
                """"downlink_mapping":{"transfer":"sdr","peak_nits":null,"sdr_white_nits":null,"applied":["hdr10plus"]}}""",
        ))
        assertEquals(VideoColor(1, 1, 1, "limited"), toSdr.downlinkColor)
        assertEquals(listOf("hdr10plus"), toSdr.downlinkMapping!!.applied)
        // A mapping without colour keeps the colour the session had.
        assertEquals(pq, mapped.describedBy(json("""{"type":"seek_ready","epoch":3,"downlink_mapping":{"transfer":"pq","applied":[]}}""")).downlinkColor)
        // One that says the colour is null, or garbles it, makes it unknown for the new epoch.
        for (color in listOf("null", "\"pq\"", """{"primaries":9}""")) {
            assertNull(color, mapped.describedBy(json(
                """{"type":"seek_ready","epoch":3,"downlink_color":$color,"downlink_mapping":{"transfer":"sdr","applied":[]}}""",
            )).downlinkColor)
        }
        // An unmapped session's seek_ready carries neither field.
        assertSame(mapped, mapped.describedBy(json("""{"type":"seek_ready","epoch":3}""")))
    }

    @Test
    fun `display targets send only plausible luminances and known transfers`() {
        assertEquals(json("""{"transfer":"sdr"}"""), DisplayTarget.SDR.toJson())
        assertEquals(
            json("""{"transfer":"pq","peak_nits":540.0,"sdr_white_nits":203.0}"""),
            DisplayTarget("pq", 540.0, 203.0).toJson(),
        )
        // The server reads anything outside 0..10000 nits as unknown.
        assertEquals(
            json("""{"transfer":"pq"}"""),
            DisplayTarget("pq", Double.POSITIVE_INFINITY, 0.0).toJson(),
        )
        assertEquals(json("""{"transfer":"pq"}"""), DisplayTarget("pq", 12_000.0, -1.0).toJson())
        assertThrows(IllegalArgumentException::class.java) { DisplayTarget("hlg") }
    }

    @Test
    fun `high bit depth modes use the protocol's names`() {
        assertEquals("hdr", HighBitDepth.HDR.wire)
        assertEquals("all", HighBitDepth.ALL.wire)
    }
}
