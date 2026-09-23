package org.upscalerelay.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.upscalerelay.protocol.SessionInfo

class AuxiliarySourcePolicyTest {
    private val session = SessionInfo.fromJson(Json.parseToJsonElement(
        """{"session_id":"s","media_port":8591,"downlink_token":"t","downlink_codec":"hevc","downlink_width":1,"downlink_height":1}""",
    ).jsonObject)

    @Test
    fun `only confirmed muxed or confirmed absent auxiliary removes external source`() {
        assertTrue(session.needsExternalMedia)
        assertTrue(session.copy(sourceHasAudio = false).needsExternalMedia)
        assertTrue(session.copy(sourceHasAudio = false, sourceHasAuxiliary = true).needsExternalMedia)
        assertFalse(session.copy(sourceHasAudio = false, sourceHasAuxiliary = false).needsExternalMedia)
        assertFalse(session.copy(auxTracks = "muxed", sourceHasAuxiliary = true).needsExternalMedia)
    }

    @Test
    fun `video only external epoch retains external confirmation across seeks`() {
        val video = session.copy(sourceHasAudio = false, sourceHasAuxiliary = false)
        val next = video.copy(epoch = 4)
        assertFalse(next.needsExternalMedia)
        assertEquals("external", next.auxTracks)
        assertEquals(false, next.sourceHasAudio)
    }
}
