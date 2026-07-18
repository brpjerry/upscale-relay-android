package org.upscalerelay.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvPlayerEngineTest {
    @Test
    fun `fixed length option preserves punctuation in media URLs`() {
        val value = "http://server:8590/media/Shows/A, B=1.mkv"
        assertEquals("%${value.length}%$value", fixedLengthOptionValue(value))
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
