package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.upscalerelay.protocol.LibraryNode

class LibraryCacheCodecTest {
    private val episode = LibraryNode(LibraryNode.Type.FILE, "Episode \"1\" [x].mkv", "Shows/A/Episode 1.mkv")
    private val season = LibraryNode(LibraryNode.Type.DIRECTORY, "A", "Shows/A", listOf(episode))
    private val shows = LibraryNode(LibraryNode.Type.DIRECTORY, "Shows", "Shows", listOf(season.copy(children = emptyList())))
    private val root = LibraryNode(LibraryNode.Type.DIRECTORY, "Library", "", listOf(shows.copy(children = emptyList())))

    @Test
    fun `snapshot round-trips the open directory and its parents`() {
        val snapshot = LibrarySnapshot("192.168.0.115:8590", season, listOf(root, shows))
        assertEquals(snapshot, LibraryCacheCodec.decode(checkNotNull(LibraryCacheCodec.encode(snapshot))))
    }

    @Test
    fun `anything that is not a current snapshot decodes to nothing`() {
        assertNull(LibraryCacheCodec.decode(""))
        assertNull(LibraryCacheCodec.decode("not json"))
        assertNull(LibraryCacheCodec.decode("""{"version":1,"origin":"h:1"}"""))
        val snapshot = LibrarySnapshot("h:1", root, emptyList())
        val encoded = checkNotNull(LibraryCacheCodec.encode(snapshot))
        assertNull(LibraryCacheCodec.decode(encoded.replace("\"version\":1", "\"version\":99")))
        // A file is never the open directory.
        assertNull(LibraryCacheCodec.decode(checkNotNull(LibraryCacheCodec.encode(snapshot.copy(directory = episode)))))
    }

    @Test
    fun `an oversized snapshot is not cached`() {
        val name = "x".repeat(1024)
        val children = (0..LibraryCacheCodec.MAX_CHARS / 1024).map {
            LibraryNode(LibraryNode.Type.FILE, name, "f$it")
        }
        assertNull(LibraryCacheCodec.encode(LibrarySnapshot("h:1", root.copy(children = children), emptyList())))
    }

    @Test
    fun `a restored view follows the user up to a parent`() {
        val restored = RestoredLibrary(
            directory = season,
            stack = listOf(root, shows),
            cursorStack = listOf("root-cursor", null),
            nextCursor = "season-cursor",
        )
        assertEquals(restored, restored.truncatedTo("Shows/A"))
        assertEquals(
            RestoredLibrary(shows, listOf(root), listOf("root-cursor"), null),
            restored.truncatedTo("Shows"),
        )
        assertEquals(
            RestoredLibrary(root, emptyList(), emptyList(), "root-cursor"),
            restored.truncatedTo(""),
        )
        // Somewhere the restore never visited: keep what was restored.
        assertEquals(restored, restored.truncatedTo("Movies"))
    }
}
