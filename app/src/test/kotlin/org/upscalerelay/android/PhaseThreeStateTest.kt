package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.upscalerelay.protocol.LibraryNode

class PhaseThreeStateTest {
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
            "server:Shows/A, B=1 [x].mkv" to 123.45,
            "local:content://provider/tree/primary%3AMovies/doc/a b.mkv" to 6.0,
        )
        assertEquals(positions, decodePositions(encodePositions(positions)))

        val many = LinkedHashMap<String, Double>()
        (1..MAX_POSITIONS + 10).forEach { many["file-$it"] = it.toDouble() }
        assertEquals(MAX_POSITIONS, decodePositions(encodePositions(many)).size)
        assertEquals(emptyMap<String, Double>(), decodePositions(""))
        assertEquals(emptyMap<String, Double>(), decodePositions("corrupt line without separator"))
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
