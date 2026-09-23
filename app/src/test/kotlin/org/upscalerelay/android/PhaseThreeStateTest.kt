package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.upscalerelay.protocol.LibraryNode

class PhaseThreeStateTest {
    @Test(expected = CancellationException::class)
    fun `cancelled playback actions do not enter fallback or failure callbacks`() {
        runPlaybackCatching<Unit> { throw CancellationException("superseded open") }
            .onFailure { throw AssertionError("cancelled open must not revive fallback UI") }
    }

    @Test
    fun `an action timeout remains a reportable failure`() = runBlocking {
        val result = runPlaybackCatching { withTimeout(1) { delay(10_000) } }
        org.junit.Assert.assertTrue(result.isFailure)
    }

    @Test
    fun `corrupt saved times cannot become seek targets or crash watched labels`() {
        val separator = Char(31)
        val lines = listOf("NaN", "Infinity", "-1.0").mapIndexed { index, value ->
            "bad-$index$separator$value"
        } + "valid${separator}42.0${separator}NaN${separator}-1"
        assertEquals(mapOf("valid" to PlaybackProgress(42.0)), decodePositions(lines.joinToString("\n")))
    }

    @Test
    fun `recent paths are newest first unique and bounded`() {
        val original = (1..MAX_RECENTS).map { "episode-$it.mkv" }
        assertEquals(
            listOf("episode-3.mkv") + original.filterNot { it == "episode-3.mkv" },
            updateRecentPaths(original, "episode-3.mkv"),
        )
        assertEquals(MAX_RECENTS, updateRecentPaths(original, "new.mkv").size)
        assertEquals("new.mkv", updateRecentPaths(original, "new.mkv").first())
    }

    @Test
    fun `playback positions round-trip with awkward keys and stay bounded`() {
        val positions = linkedMapOf(
            "server:Shows/A, B=1 [x].mkv" to PlaybackProgress(123.45, 3600.0, 1_721_000_000_000L),
            "local:content://provider/tree/primary%3AMovies/doc/a b.mkv" to PlaybackProgress(6.0),
        )
        assertEquals(positions, decodePositions(encodePositions(positions)))

        val many = LinkedHashMap<String, PlaybackProgress>()
        (1..MAX_POSITIONS + 10).forEach { many["file-$it"] = PlaybackProgress(it.toDouble()) }
        assertEquals(MAX_POSITIONS, decodePositions(encodePositions(many)).size)
        assertEquals(emptyMap<String, PlaybackProgress>(), decodePositions(""))
        assertEquals(
            emptyMap<String, PlaybackProgress>(),
            decodePositions("corrupt line without separator"),
        )
    }

    @Test
    fun `decode honours a configurable history limit`() {
        val many = LinkedHashMap<String, PlaybackProgress>()
        (1..MAX_POSITIONS + 70).forEach { many["file-$it"] = PlaybackProgress(it.toDouble()) }
        val encoded = encodePositions(many)
        assertEquals(MAX_POSITIONS + 70, decodePositions(encoded, MAX_POSITIONS_LIMIT).size)
        assertEquals(10, decodePositions(encoded, 10).size)
        // Insertion order is most recent first, so a lower limit keeps the newest.
        assertEquals("file-1", decodePositions(encoded, 10).keys.first())
    }

    @Test
    fun `legacy two-field position lines decode with unknown duration and timestamp`() {
        val separator = Char(31)
        assertEquals(
            mapOf("server:old.mkv" to PlaybackProgress(42.5)),
            decodePositions("server:old.mkv${separator}42.5"),
        )
    }

    @Test
    fun `library lookup traverses directories and rejects unknown paths`() {
        val episode = LibraryNode(LibraryNode.Type.FILE, "Episode", "Shows/Episode.mkv")
        val root = LibraryNode(
            LibraryNode.Type.DIRECTORY,
            "Library",
            "",
            listOf(LibraryNode(LibraryNode.Type.DIRECTORY, "Shows", "Shows", listOf(episode))),
        )
        assertEquals(episode, root.findPath("Shows/Episode.mkv"))
        assertNull(root.findPath("Missing.mkv"))
    }
}
