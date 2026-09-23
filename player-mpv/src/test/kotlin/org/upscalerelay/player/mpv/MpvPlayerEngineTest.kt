package org.upscalerelay.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvPlayerEngineTest {
    @Test
    fun `relay load tolerates an indefinitely silent loopback stream`() {
        assertEquals("network-timeout=0,pause=yes", relayLoadOptions())
    }

    @Test
    fun `relay load does not attach the original media up front`() {
        // Attaching here would leave mpv's external demuxers at the start of
        // the original file rather than at the epoch's position.
        assertEquals(false, relayLoadOptions().contains("audio-file"))
        assertEquals(false, relayLoadOptions().contains("sub-files"))
    }

    @Test
    fun `relay load holds the epoch paused until its audio is attached`() {
        // Without this the picture runs on alone while the attach completes
        // and mpv reconciles the drift by dropping frames on every start.
        assertEquals(true, relayLoadOptions().contains("pause=yes"))
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

    @Test
    fun `track choices remap by descriptor and occurrence instead of numeric id`() {
        val original = listOf(
            track(2, MpvTrack.Type.AUDIO, "jpn", "Main", "aac"),
            track(3, MpvTrack.Type.AUDIO, "jpn", "Main", "aac"),
        )
        val remembered = rememberTrack(original, MpvTrack.Type.AUDIO, 3)
        val reloaded = listOf(
            track(9, MpvTrack.Type.AUDIO, "jpn", "Main", "aac"),
            track(10, MpvTrack.Type.AUDIO, "jpn", "Main", "aac"),
        )
        assertEquals(10, remapTrack(reloaded, remembered))
    }

    @Test
    fun `numeric id is reused only while its descriptor still matches`() {
        val remembered = rememberTrack(
            listOf(track(2, MpvTrack.Type.SUBTITLE, "eng", "Signs", "ass")),
            MpvTrack.Type.SUBTITLE,
            2,
        )
        val reloaded = listOf(
            track(2, MpvTrack.Type.SUBTITLE, "spa", "Dialogue", "ass"),
            track(7, MpvTrack.Type.SUBTITLE, "eng", "Signs", "ass"),
        )
        assertEquals(7, remapTrack(reloaded, remembered))
    }

    @Test
    fun `audio and subtitle numeric ids have separate namespaces`() {
        val original = listOf(
            track(1, MpvTrack.Type.AUDIO, "jpn", "Main", "aac"),
            track(1, MpvTrack.Type.SUBTITLE, "eng", "Signs", "ass"),
        )
        val subtitle = rememberTrack(original, MpvTrack.Type.SUBTITLE, 1)
        val reloaded = listOf(
            track(1, MpvTrack.Type.AUDIO, "jpn", "Main", "aac"),
            track(3, MpvTrack.Type.SUBTITLE, "eng", "Signs", "ass"),
        )
        assertEquals(3, remapTrack(reloaded, subtitle))
        assertEquals(null, remapTrack(reloaded, null)) // subtitles off
    }

    @Test
    fun `missing remembered track never selects an unrelated replacement`() {
        val remembered = rememberTrack(
            listOf(track(1, MpvTrack.Type.SUBTITLE, "eng", "Signs", "ass")),
            MpvTrack.Type.SUBTITLE,
            1,
        )
        assertEquals(null, remapTrack(listOf(track(1, MpvTrack.Type.SUBTITLE, "spa", "Dialogue", "ass")), remembered))
    }

    @Test
    fun `subtitle only sources attach once without opening an audio demuxer`() {
        assertEquals("sub-add", externalAttachCommand(RelayAuxMode.EXTERNAL_SUBTITLES))
        assertEquals("audio-add", externalAttachCommand(RelayAuxMode.EXTERNAL))
    }

    @Test(expected = IllegalStateException::class)
    fun `muxed epochs cannot attach an external demuxer`() {
        externalAttachCommand(RelayAuxMode.MUXED)
    }

    @Test(expected = IllegalStateException::class)
    fun `video only epochs cannot attach an external demuxer`() {
        externalAttachCommand(RelayAuxMode.NONE)
    }

    private fun track(
        id: Int,
        type: MpvTrack.Type,
        language: String,
        title: String,
        codec: String,
    ) = MpvTrack(id, type, language, title, codec, selected = false, external = false)
}
