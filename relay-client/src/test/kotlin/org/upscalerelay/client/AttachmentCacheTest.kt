package org.upscalerelay.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.AttachmentManifestEntry
import org.upscalerelay.protocol.DisplaySize
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class AttachmentCacheTest {
    @Test
    fun `server-file negotiation fields are capability gated`() {
        val old = serverFileOpenMessage(
            "movie.mkv", "passthrough", DisplaySize(1920, 1080), "lossless-hevc", "fit", null,
            requestMuxedAuxTracks = false,
            requestCachedAttachments = false,
        )
        assertFalse("aux_tracks" in old)
        assertFalse("aux_attachments" in old)

        val embedded = serverFileOpenMessage(
            "movie.mkv", "passthrough", DisplaySize(1920, 1080), "lossless-hevc", "fit", null,
            requestMuxedAuxTracks = true,
            requestCachedAttachments = false,
        )
        assertEquals("muxed", embedded.getValue("aux_tracks").jsonPrimitive.content)
        assertEquals("embedded", embedded.getValue("aux_attachments").jsonPrimitive.content)

        val cached = serverFileOpenMessage(
            "movie.mkv", "passthrough", DisplaySize(1920, 1080), "lossless-hevc", "fit", null,
            requestMuxedAuxTracks = true,
            requestCachedAttachments = true,
        )
        assertEquals("cached", cached.getValue("aux_attachments").jsonPrimitive.content)
    }

    @Test
    fun `cache miss downloads once and later session is a verified hit`() = withTempDirectory { root ->
        val data = "font bytes".encodeToByteArray()
        val entry = entry("font.ttf", data)
        var requests = 0
        val cache = AttachmentCache(root, AttachmentFetcher { digest, token, destination, _ ->
            assertEquals(entry.sha256, digest)
            assertEquals("secret", token)
            requests += 1
            Files.write(destination, data)
            data.size.toLong()
        })
        runBlocking {
            val first = cache.materialize("one", listOf(entry), "secret")
            val second = cache.materialize("two", listOf(entry), "secret")
            assertEquals(1, requests)
            assertEquals(1, first.stats.misses)
            assertEquals(1, second.stats.hits)
            assertTrue(Files.isRegularFile(first.directory.resolve("font.ttf")))
            assertTrue(Files.isRegularFile(second.directory.resolve("font.ttf")))
        }
    }

    @Test
    fun `mismatched or interrupted body never publishes an object`() = withTempDirectory { root ->
        val expected = "expected".encodeToByteArray()
        val entry = entry("font.ttf", expected)
        val cache = AttachmentCache(root, AttachmentFetcher { _, _, destination, _ ->
            Files.write(destination, "corrupt!".encodeToByteArray())
            throw java.io.IOException("connection lost")
        })
        runCatching { runBlocking { cache.materialize("bad", listOf(entry), "secret") } }
        assertFalse(Files.exists(root.resolve("objects").resolve(entry.sha256)))
        Files.list(root.resolve("objects")).use { files ->
            assertEquals(0L, files.count())
        }

        val mismatch = AttachmentCache(root, AttachmentFetcher { _, _, destination, _ ->
            val corrupt = "corrupt!".encodeToByteArray()
            Files.write(destination, corrupt)
            corrupt.size.toLong()
        })
        runCatching { runBlocking { mismatch.materialize("mismatch", listOf(entry), "secret") } }
        assertFalse(Files.exists(root.resolve("objects").resolve(entry.sha256)))
    }

    @Test
    fun `duplicate hashes download once and duplicate names get stable suffixes`() = withTempDirectory { root ->
        val data = "same font".encodeToByteArray()
        val first = entry("font.ttf", data)
        val second = first.copy(name = "font.ttf")
        var requests = 0
        val cache = AttachmentCache(root, AttachmentFetcher { _, _, destination, _ ->
            requests += 1
            Files.write(destination, data)
            data.size.toLong()
        })
        val result = runBlocking { cache.materialize("dupes", listOf(first, second), "secret") }
        assertEquals(1, requests)
        val names = Files.list(result.directory).use { it.map(Path::getFileName).map(Path::toString).sorted().toList() }
        assertEquals(listOf("font-${first.sha256.take(8)}.ttf", "font.ttf"), names)
    }

    @Test
    fun `eviction protects the active view and teardown removes only that view`() = withTempDirectory { root ->
        val firstData = "first font".encodeToByteArray()
        val secondData = "second font".encodeToByteArray()
        val bodies = listOf(firstData, secondData).associateBy(::sha256)
        val cache = AttachmentCache(
            root,
            AttachmentFetcher { digest, _, destination, _ ->
                val data = bodies.getValue(digest)
                Files.write(destination, data)
                data.size.toLong()
            },
            maxCacheBytes = secondData.size.toLong(),
        )
        runBlocking {
            val first = cache.materialize("first", listOf(entry("first.ttf", firstData)), "secret")
            val secondEntry = entry("second.ttf", secondData)
            val second = cache.materialize("second", listOf(secondEntry), "secret")
            assertEquals(1, second.stats.evictions)
            assertTrue(Files.exists(root.resolve("objects").resolve(secondEntry.sha256)))
            cache.removeView(second.directory)
            assertFalse(Files.exists(second.directory))
            assertTrue(Files.exists(root.resolve("objects").resolve(secondEntry.sha256)))
            // A view can survive its object's later eviction because it is a
            // hard link/copy; teardown remains scoped to that session folder.
            cache.removeView(first.directory)
        }
    }

    private fun entry(name: String, data: ByteArray): AttachmentManifestEntry =
        AttachmentManifestEntry(
            name = name,
            mimeType = "font/ttf",
            size = data.size.toLong(),
            sha256 = sha256(data),
        )

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("relay-attachment-test")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
