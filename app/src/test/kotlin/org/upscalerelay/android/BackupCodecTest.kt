package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BackupCodecTest {
    private val populated = AppPreferences(
        host = "10.0.0.5",
        port = 9001,
        autoConnect = true,
        autoResume = false,
        autoPlayNext = false,
        model = "2x_AnimeJaNai_HD_V3_Compact",
        qualityTier = "hevc-qp10",
        fitMode = "cover",
        resizeAlgorithm = "lanczos",
        debandEnabled = true,
        subtitlesEnabled = false,
        preferredSubtitle = "eng\u001FSigns",
        diagnosticsVisible = true,
        gesturesEnabled = false,
        displayResampleSync = true,
        interpolationEnabled = true,
        interpolationScaler = "mitchell",
        backgroundPlayback = false,
        fileLoggingEnabled = true,
        librarySort = "DATE",
        lastDestination = "LOCAL",
        lastLibraryPath = "Shows/Season 1",
        recentPaths = listOf("Shows/a.mkv", "Shows/b.mkv"),
        recentLocalUris = listOf("content://tree/doc/a.mkv"),
        recentLocalRootUris = listOf("content://tree/primary%3AMovies"),
        playbackPositions = linkedMapOf(
            "server:Shows/a, b [x].mkv" to PlaybackProgress(123.45, 1430.0, 1_721_000_000_000L),
            "local:content://provider/doc/a b.mkv" to PlaybackProgress(6.0),
        ),
        playbackHistoryLimit = 250,
    )

    private fun roundTrip(value: AppPreferences, onto: AppPreferences = AppPreferences()) =
        BackupCodec.decode(BackupCodec.encode(value, "0.15.0", Instant.EPOCH), onto)

    @Test
    fun `every persisted field survives a round trip`() {
        assertEquals(populated, roundTrip(populated))
    }

    @Test
    fun `defaults round-trip unchanged`() {
        assertEquals(AppPreferences(), roundTrip(AppPreferences(), populated))
    }

    @Test
    fun `export is human readable and carries iso timestamps`() {
        val text = BackupCodec.encode(populated, "0.15.0", Instant.ofEpochMilli(1_721_000_000_000L))
        assertTrue(text.contains("\n  \"connection\": {"))
        assertTrue(text.contains("\"exportedAt\": \"2024-07-14T23:33:20Z\""))
        // Each history row carries the readable stamp beside the raw millis.
        assertTrue(text.contains("\"lastPlayedAt\": \"2024-07-14T23:33:20Z\""))
        assertTrue(text.contains("\"appVersion\": \"0.15.0\""))
    }

    @Test
    fun `absent sections keep what the device already has`() {
        val partial = """
            {
              "format": "${BackupCodec.FORMAT}",
              "version": 1,
              "connection": { "host": "192.168.1.9" }
            }
        """.trimIndent()
        val result = BackupCodec.decode(partial, populated)
        assertEquals("192.168.1.9", result.host)
        // Untouched by the file, so the device's own values stand.
        assertEquals(populated.port, result.port)
        assertEquals(populated.playbackPositions, result.playbackPositions)
        assertEquals(populated.interpolationScaler, result.interpolationScaler)
    }

    @Test
    fun `out of range values fall back instead of corrupting the store`() {
        val hostile = """
            {
              "format": "${BackupCodec.FORMAT}",
              "version": 1,
              "connection": { "host": "  ", "port": 70000 },
              "playbackDefaults": { "fitMode": "stretch" },
              "player": { "interpolationScaler": "bogus", "playbackHistoryLimit": 99999 },
              "library": { "librarySort": "SIZE", "lastDestination": "NOWHERE" }
            }
        """.trimIndent()
        val result = BackupCodec.decode(hostile, populated)
        assertEquals(populated.host, result.host)
        assertEquals(populated.port, result.port)
        assertEquals(populated.fitMode, result.fitMode)
        assertEquals(populated.interpolationScaler, result.interpolationScaler)
        assertEquals(MAX_POSITIONS_LIMIT, result.playbackHistoryLimit)
        assertEquals(populated.librarySort, result.librarySort)
        assertEquals(populated.lastDestination, result.lastDestination)
    }

    @Test
    fun `history rows that would corrupt the store are dropped`() {
        val history = """
            {
              "format": "${BackupCodec.FORMAT}",
              "version": 1,
              "watchHistory": [
                { "key": "server:good.mkv", "positionSeconds": 12.5, "durationSeconds": 60.0 },
                { "key": "server:sep\u001Fbad.mkv", "positionSeconds": 1.0 },
                { "key": "server:newline\nbad.mkv", "positionSeconds": 1.0 },
                { "key": "", "positionSeconds": 1.0 },
                { "key": "server:no-position.mkv" },
                { "key": "server:negative.mkv", "positionSeconds": -5.0 }
              ]
            }
        """.trimIndent()
        val result = BackupCodec.decode(history, AppPreferences())
        assertEquals(
            mapOf("server:good.mkv" to PlaybackProgress(12.5, 60.0, 0L)),
            result.playbackPositions,
        )
    }

    @Test
    fun `history is trimmed to the imported limit and keeps file order`() {
        val many = LinkedHashMap<String, PlaybackProgress>()
        (1..40).forEach { many["file-$it"] = PlaybackProgress(it.toDouble()) }
        val source = populated.copy(playbackPositions = many, playbackHistoryLimit = 10)
        val result = roundTrip(source)
        assertEquals(10, result.playbackPositions.size)
        assertEquals(listOf("file-1", "file-2"), result.playbackPositions.keys.take(2))
    }

    @Test
    fun `foreign and future files are rejected with a readable message`() {
        val notJson = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode("not json at all", AppPreferences())
        }
        assertTrue(notJson.message!!.contains("valid JSON"))

        val notOurs = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode("""{"format":"something-else","version":1}""", AppPreferences())
        }
        assertTrue(notOurs.message!!.contains("Upscale Relay backup"))

        val future = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(
                """{"format":"${BackupCodec.FORMAT}","version":${BackupCodec.VERSION + 1}}""",
                AppPreferences(),
            )
        }
        assertTrue(future.message!!.contains("newer version"))
    }

    @Test
    fun `suggested file name is sortable and unique per second`() {
        val first = BackupCodec.suggestedFileName(
            Instant.parse("2026-07-25T21:30:05Z"),
            java.time.ZoneOffset.UTC,
        )
        val second = BackupCodec.suggestedFileName(
            Instant.parse("2026-07-25T21:30:06Z"),
            java.time.ZoneOffset.UTC,
        )
        assertEquals("upscale-relay-backup-20260725-213005.json", first)
        assertNotEquals(first, second)
    }
}
