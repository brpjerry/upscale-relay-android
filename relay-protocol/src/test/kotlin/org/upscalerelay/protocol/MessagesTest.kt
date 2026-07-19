package org.upscalerelay.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {
    @Test
    fun `capabilities choose a real model before passthrough`() {
        val value = Json.parseToJsonElement(
            """{"protocol_version":1,"server_name":"relay","models":[{"name":"passthrough","scale_factor":1},{"name":"anime-x2","scale_factor":2}],"quality_tiers":["lossless-hevc"],"library":true}""",
        ).jsonObject
        assertEquals("anime-x2", Capabilities.fromJson(value).phaseOneModel)
        assertEquals(listOf("lanczos"), Capabilities.fromJson(value).resizeAlgorithms)
    }

    @Test
    fun `server file session keeps null uplink`() {
        val value = Json.parseToJsonElement(
            """{"session_id":"s","media_port":8591,"uplink_token":null,"downlink_token":"0123456789abcdef0123456789abcdef01","downlink_codec":"hevc","downlink_width":2960,"downlink_height":1664,"downlink_container":"matroska","epoch":0,"duration_s":1420.1,"time_base":[1,1000],"avg_rate":[24000,1001]}""",
        ).jsonObject
        val session = SessionInfo.fromJson(value)
        assertNull(session.uplinkToken)
        assertEquals("matroska", session.downlinkContainer)
        assertEquals(0, session.epoch)
        assertEquals(Rational(1, 1000), session.timeBase)
        assertEquals(Rational(24000, 1001), session.averageRate)
        assertEquals(1420.1, session.durationSeconds!!, 0.0001)
    }

    @Test
    fun `resize capabilities and selected session algorithm are parsed`() {
        val capabilities = Json.parseToJsonElement(
            """{"protocol_version":1,"server_name":"relay","models":[],"quality_tiers":[],"resize_algorithms":["area","lanczos"],"default_resize_algorithm":"area"}""",
        ).jsonObject
        assertEquals(listOf("area", "lanczos"), Capabilities.fromJson(capabilities).resizeAlgorithms)
        assertEquals("area", Capabilities.fromJson(capabilities).defaultResizeAlgorithm)

        val session = Json.parseToJsonElement(
            """{"session_id":"s","media_port":8591,"uplink_token":null,"downlink_token":"0123456789abcdef0123456789abcdef01","downlink_codec":"hevc","downlink_width":2960,"downlink_height":1848,"epoch":0,"fit_mode":"cover","resize_algorithm":"spline"}""",
        ).jsonObject
        assertEquals("cover", SessionInfo.fromJson(session).fitMode)
        assertEquals("spline", SessionInfo.fromJson(session).resizeAlgorithm)
    }

    @Test
    fun `session chapters are parsed sorted with junk entries dropped`() {
        val value = Json.parseToJsonElement(
            """{"session_id":"s","media_port":8591,"uplink_token":null,"downlink_token":"0123456789abcdef0123456789abcdef01","downlink_codec":"hevc","downlink_width":1920,"downlink_height":1080,"epoch":0,"chapters":[{"start_s":95.5,"end_s":null,"title":null},{"start_s":0.0,"end_s":95.5,"title":"Opening"},{"start_s":-2.0,"title":"bad"},{"title":"no start"},{"start_s":10.0,"title":""}]}""",
        ).jsonObject
        val chapters = SessionInfo.fromJson(value).chapters
        assertEquals(3, chapters.size)
        assertEquals(ChapterInfo(0.0, 95.5, "Opening"), chapters[0])
        assertEquals(ChapterInfo(10.0, null, null), chapters[1]) // blank title dropped
        assertEquals(ChapterInfo(95.5, null, null), chapters[2])
    }

    @Test
    fun `missing or null chapters mean an empty list`() {
        val base = """{"session_id":"s","media_port":8591,"uplink_token":null,"downlink_token":"t","downlink_codec":"hevc","downlink_width":1,"downlink_height":1,"epoch":0"""
        val absent = Json.parseToJsonElement("$base}").jsonObject
        val explicitNull = Json.parseToJsonElement("""$base,"chapters":null}""").jsonObject
        assertEquals(emptyList<ChapterInfo>(), SessionInfo.fromJson(absent).chapters)
        assertEquals(emptyList<ChapterInfo>(), SessionInfo.fromJson(explicitNull).chapters)
    }

    @Test
    fun `structured quality options preserve labels and Android support`() {
        val value = Json.parseToJsonElement(
            """{"protocol_version":1,"server_name":"relay","models":[],"quality_tiers":["hevc-qp2","lossless-ffv1"],"quality_options":[{"id":"hevc-qp2","label":"HEVC ~350 Mbps","codec":"hevc","lossless":false,"android_supported":true,"p95_mbps":350},{"id":"lossless-ffv1","label":"Lossless FFV1","codec":"ffv1","lossless":true,"android_supported":false,"p95_mbps":null}]}""",
        ).jsonObject
        val options = Capabilities.fromJson(value).qualityOptions
        assertEquals("HEVC ~350 Mbps", options[0].label)
        assertEquals(true, options[0].androidSupported)
        assertEquals(350, options[0].p95Mbps)
        assertEquals(false, options[1].androidSupported)
        assertNull(options[1].p95Mbps)
    }
}
