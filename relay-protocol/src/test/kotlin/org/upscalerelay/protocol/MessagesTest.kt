package org.upscalerelay.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    @Test
    fun `source presence preserves explicit false separately from missing or null`() {
        val base = """{"session_id":"s","media_port":8591,"downlink_token":"t","downlink_codec":"hevc","downlink_width":1,"downlink_height":1"""
        for (suffix in listOf("}", ""","source_has_audio":null,"source_has_auxiliary":null}""")) {
            val session = SessionInfo.fromJson(Json.parseToJsonElement(base + suffix).jsonObject)
            assertNull(session.sourceHasAudio)
            assertNull(session.sourceHasAuxiliary)
        }
        val subtitles = SessionInfo.fromJson(Json.parseToJsonElement(
            """$base,"source_has_audio":false,"source_has_auxiliary":true}""",
        ).jsonObject)
        assertEquals(false, subtitles.sourceHasAudio)
        assertEquals(true, subtitles.sourceHasAuxiliary)
        val video = SessionInfo.fromJson(Json.parseToJsonElement(
            """$base,"source_has_audio":false,"source_has_auxiliary":false}""",
        ).jsonObject)
        assertEquals(false, video.sourceHasAuxiliary)
    }

    @Test
    fun `seek progress tolerates absent nullable and future stage metadata`() {
        val basic = SeekProgress.fromJson(Json.parseToJsonElement(
            """{"epoch":4,"target_pts":900000,"keyframe_pts":null,"frames_discarded":25,"elapsed_s":1.25}""",
        ).jsonObject)
        assertEquals(4, basic.epoch)
        assertNull(basic.stage)
        assertNull(basic.message)
        assertNull(basic.subtitleIndexedSeconds)
        val future = SeekProgress.fromJson(Json.parseToJsonElement(
            """{"epoch":4,"target_pts":900000,"stage":"future_stage","message":null,"subtitle_indexed_s":null}""",
        ).jsonObject)
        assertEquals("future_stage", future.stage)
        assertNull(future.message)
        val indexing = SeekProgress.fromJson(Json.parseToJsonElement(
            """{"epoch":4,"target_pts":900000,"stage":"subtitle_index","subtitle_indexed_s":456.75,"message":"Indexing subtitles for this seek"}""",
        ).jsonObject)
        assertEquals(456.75, indexing.subtitleIndexedSeconds!!, 0.001)
    }

    @Test
    fun `library pages preserve cursor and shallow children`() {
        val value = Json.parseToJsonElement(
            """{"tree":{"type":"directory","name":"Library","path":"","children":[{"type":"directory","name":"Shows","path":"Shows","children":[]}]},"next_cursor":"100"}""",
        ).jsonObject
        val page = LibraryPage.fromJson(value)
        assertEquals("100", page.nextCursor)
        assertEquals("Shows", page.directory.children.single().path)

        val finalPage = Json.parseToJsonElement(
            """{"tree":{"type":"directory","name":"Library","path":"","children":[]}}""",
        ).jsonObject
        assertNull(LibraryPage.fromJson(finalPage).nextCursor)
    }

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

    @Test
    fun `auxiliary capabilities are additive and default off`() {
        val base = """{"protocol_version":1,"server_name":"relay","models":[],"quality_tiers":[]"""
        val old = Capabilities.fromJson(Json.parseToJsonElement("$base}").jsonObject)
        assertEquals(false, old.muxedAuxTracks)
        assertEquals(0, old.attachmentCacheVersion)

        val current = Capabilities.fromJson(
            Json.parseToJsonElement("""$base,"muxed_aux_tracks":true,"attachment_cache":1}""").jsonObject,
        )
        assertEquals(true, current.muxedAuxTracks)
        assertEquals(1, current.attachmentCacheVersion)
    }

    @Test
    fun `session auxiliary confirmations are authoritative and tolerant`() {
        val base = """{"session_id":"s","media_port":8591,"uplink_token":null,"downlink_token":"t","downlink_codec":"hevc","downlink_width":1,"downlink_height":1,"epoch":0"""
        val old = SessionInfo.fromJson(Json.parseToJsonElement("$base}").jsonObject)
        assertEquals("external", old.auxTracks)
        assertEquals("embedded", old.auxAttachments)
        assertEquals("server_file", old.source)

        val muxed = SessionInfo.fromJson(
            Json.parseToJsonElement("""$base,"source":"server_file","aux_tracks":"muxed","aux_attachments":"embedded"}""").jsonObject,
        )
        assertEquals("muxed", muxed.auxTracks)
        assertEquals("embedded", muxed.auxAttachments)

        val future = SessionInfo.fromJson(
            Json.parseToJsonElement("""$base,"aux_tracks":"future","aux_attachments":"remote"}""").jsonObject,
        )
        assertEquals("external", future.auxTracks)
        assertEquals("embedded", future.auxAttachments)
    }

    @Test
    fun `cached attachment manifest is bounded sanitized and authenticated`() {
        val digest = "a".repeat(64)
        val value = Json.parseToJsonElement(
            """{"session_id":"s","media_port":8591,"uplink_token":null,"downlink_token":"t","downlink_codec":"hevc","downlink_width":1,"downlink_height":1,"epoch":0,"aux_tracks":"muxed","aux_attachments":"cached","attachment_manifest":[{"name":"../../unsafe font.ttf","mimetype":"font/ttf","size":3,"sha256":"$digest"}],"attachment_token":"secret"}""",
        ).jsonObject
        val session = SessionInfo.fromJson(value)
        assertEquals("cached", session.auxAttachments)
        assertEquals("unsafe_font.ttf", session.attachmentManifest.single().name)
        assertEquals(3L, session.attachmentManifest.single().size)
        assertTrue(session.attachmentToken?.isNotBlank() == true)
    }

    @Test
    fun `cached attachment manifest rejects unsafe metadata before IO`() {
        val prefix = """{"session_id":"s","media_port":8591,"uplink_token":null,"downlink_token":"t","downlink_codec":"hevc","downlink_width":1,"downlink_height":1,"epoch":0,"aux_tracks":"muxed","aux_attachments":"cached""" + '"'
        assertThrows(IllegalArgumentException::class.java) {
            SessionInfo.fromJson(
                Json.parseToJsonElement("""$prefix,"attachment_manifest":[{"name":"x","size":0,"sha256":"../bad"}],"attachment_token":"secret"}""").jsonObject,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            SessionInfo.fromJson(
                Json.parseToJsonElement("""$prefix,"attachment_manifest":[]}""").jsonObject,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionInfo.fromJson(
                Json.parseToJsonElement("""$prefix,"attachment_manifest":[{"name":"x","size":67108865,"sha256":"${"b".repeat(64)}"}],"attachment_token":"secret"}""").jsonObject,
            )
        }
    }
}
