package org.upscalerelay.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md §5's buffer target and §1.2's session resume, as the wire carries them. */
class ResumeAndBufferTargetTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val opened = """{"session_id":"s","media_port":8591,"downlink_token":"t","downlink_codec":"hevc","downlink_width":1,"downlink_height":1"""

    // Bugs caught: 0, False positives: 0
    @Test
    fun `session_opened confirms a buffer target only as a positive number`() {
        assertEquals(240_000L, SessionInfo.fromJson(json("""$opened,"buffer_target_ms":240000}""")).bufferTargetMillis)
        // A server that predates the field, and garbage, both mean the 10 s default.
        for (field in listOf("", ""","buffer_target_ms":null""", ""","buffer_target_ms":"240000"""", ""","buffer_target_ms":0""")) {
            assertNull(field, SessionInfo.fromJson(json("$opened$field}")).bufferTargetMillis)
        }
    }

    // Bugs caught: 0, False positives: 0
    @Test
    fun `a resume token counts only together with its grace period`() {
        val both = SessionInfo.fromJson(json("""$opened,"resume_token":"secret","resume_grace_s":120.0}"""))
        assertEquals("secret", both.resumeToken)
        assertEquals(120.0, both.resumeGraceSeconds!!, 0.0)
        for (fields in listOf(
            ""","resume_token":"secret"""",
            ""","resume_grace_s":120""",
            ""","resume_token":"","resume_grace_s":120""",
            ""","resume_token":"secret","resume_grace_s":0""",
            ""","resume_token":7,"resume_grace_s":120""",
        )) {
            val session = SessionInfo.fromJson(json("$opened$fields}"))
            assertNull(fields, session.resumeToken)
            assertNull(fields, session.resumeGraceSeconds)
        }
    }

    // Bugs caught: 0, False positives: 0
    @Test
    fun `the session's printed form never carries its tokens`() {
        val session = SessionInfo.fromJson(json("""$opened,"resume_token":"resume-secret","resume_grace_s":120}"""))
            .copy(attachmentToken = "attachment-secret")
        val printed = session.toString()
        assertFalse(printed, "resume-secret" in printed)
        assertFalse(printed, "attachment-secret" in printed)
        assertFalse(printed, "\"t\"" in printed || "Token=t" in printed)
        assertTrue(printed, "resumable=true" in printed)
    }

    // Bugs caught: 0, False positives: 0
    @Test
    fun `capabilities advertise session resume only as a boolean true`() {
        val base = """{"protocol_version":1,"server_name":"s","models":[],"quality_tiers":["lossless-hevc"]"""
        assertTrue(Capabilities.fromJson(json("""$base,"session_resume":true}""")).sessionResume)
        for (field in listOf("", ""","session_resume":false""", ""","session_resume":"true"""")) {
            assertFalse(field, Capabilities.fromJson(json("$base$field}")).sessionResume)
        }
    }

    // Bugs caught: 0, False positives: 0
    @Test
    fun `session_resumed answers a lost seek_ready with the current epoch's colour only`() {
        val resumed = SessionResumed.fromJson(json(
            """{"type":"session_resumed","session_id":"s","state":"playing","epoch":3,""" +
                """"uplink_attached":false,"downlink_attached":true,"buffer_target_ms":60000,""" +
                """"downlink_color":{"primaries":9,"transfer":16,"matrix":9},"downlink_mapping":null}""",
        ))
        assertEquals("playing", resumed.state)
        assertEquals(false, resumed.uplinkAttached)
        assertEquals(true, resumed.downlinkAttached)
        assertEquals(60_000L, resumed.bufferTargetMillis)
        val current = resumed.seekReadyFor(3)
        assertEquals(3, current.getValue("epoch").jsonPrimitive.int)
        assertTrue("downlink_color" in current && "downlink_mapping" in current)
        // An earlier epoch's colour is not this reply's to give.
        val earlier = resumed.seekReadyFor(2)
        assertFalse("downlink_color" in earlier || "downlink_mapping" in earlier)
        // The epoch that seek_ready describes takes the reply's colour as a seek_ready would.
        val session = SessionInfo.fromJson(json("$opened}")).describedBy(current)
        assertEquals(16, session.downlinkColor?.transfer)
    }

    // Bugs caught: 0, False positives: 1
    @Test
    fun `a target sized by the cache follows the bitrate it holds`() {
        val mib = 1024L * 1024
        // 768 MiB holding 20 s in 100 MiB: the whole cache lasts 153.6 s.
        assertEquals(153_600L, BufferTarget.forCache(768 * mib, 20_000, 100 * mib))
        // Under a second says nothing, unless the cache is already (nearly) full.
        assertNull(BufferTarget.forCache(768 * mib, 900, 10 * mib))
        // 240 MiB of 256 in 0.8 s: the whole cache is 0.85 s.
        assertEquals(853L, BufferTarget.forCache(256 * mib, 800, 240 * mib))
        assertNull(BufferTarget.forCache(768 * mib, 0, 10 * mib))
        assertNull(BufferTarget.forCache(768 * mib, 5_000, 0))
    }

    // Bugs caught: 0, False positives: 0
    @Test
    fun `targets go out as the server applies them`() {
        assertEquals(2_000L, BufferTarget.normalize(800))
        assertEquals(153_600L, BufferTarget.normalize(153_600))
        assertNull(BufferTarget.normalize(0))
        assertNull(BufferTarget.normalize(null))
        // 768 MiB at 40 Mbps opens at about 161 s.
        assertEquals(161_061L, BufferTarget.estimate(768L * 1024 * 1024, 40.0))
    }
}
