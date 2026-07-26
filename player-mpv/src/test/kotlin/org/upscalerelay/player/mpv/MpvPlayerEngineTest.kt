package org.upscalerelay.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvPlayerEngineTest {
    @Test
    fun `relay load tolerates an indefinitely silent loopback stream`() {
        assertEquals("network-timeout=0", relayLoadOptions())
    }

    @Test
    fun `relay load does not attach the original media up front`() {
        // Attaching here would leave mpv's external demuxers at the start of
        // the original file rather than at the epoch's position.
        assertEquals(false, relayLoadOptions().contains("audio-file"))
        assertEquals(false, relayLoadOptions().contains("sub-files"))
    }

    @Test
    fun `forwarded mpv log lines have media URLs redacted`() {
        assertEquals(
            "Failed to open <url>",
            MpvPlayerEngine.redactUrls("Failed to open http://server:8590/media/Shows/Secret%20Episode.mkv"),
        )
        assertEquals(
            "stream <url> ended, audio <url>",
            MpvPlayerEngine.redactUrls(
                "stream tcp://127.0.0.1:40123 ended, audio http://127.0.0.1:8123/doc",
            ),
        )
        assertEquals("no urls here 10/20", MpvPlayerEngine.redactUrls("no urls here 10/20"))
    }
}
