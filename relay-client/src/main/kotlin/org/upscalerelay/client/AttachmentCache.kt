package org.upscalerelay.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.upscalerelay.protocol.AttachmentManifestEntry
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.io.path.name

fun interface AttachmentFetcher {
    /** Writes one response body to [destination] and returns its byte count. */
    suspend fun fetch(sha256: String, token: String, destination: Path, maxBytes: Long): Long
}

data class AttachmentCacheStats(
    val hits: Int = 0,
    val misses: Int = 0,
    val verifiedBytes: Long = 0,
    val evictions: Int = 0,
)

data class AttachmentCacheResult(
    val directory: Path,
    val stats: AttachmentCacheStats,
)

/**
 * Disposable, content-addressed subtitle-font cache. Object publication is
 * exact-size/SHA-256 verified and atomic; per-session views retain font-like
 * names for libass while the persistent objects remain hash-addressed.
 */
class AttachmentCache(
    private val root: Path,
    private val fetcher: AttachmentFetcher,
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
) {
    suspend fun materialize(
        sessionId: String,
        manifest: List<AttachmentManifestEntry>,
        token: String,
    ): AttachmentCacheResult {
        require(token.isNotBlank()) { "cached attachment session omitted its token" }
        val objects = root.resolve("objects")
        withContext(Dispatchers.IO) { Files.createDirectories(objects) }

        var hits = 0
        var misses = 0
        var verifiedBytes = 0L
        val unique = manifest.distinctBy { it.sha256 }
        for (entry in unique) {
            val target = objects.resolve(entry.sha256)
            if (withContext(Dispatchers.IO) { verifyObject(target, entry) }) {
                hits += 1
                verifiedBytes += entry.size
                continue
            }
            misses += 1
            withContext(Dispatchers.IO) { Files.deleteIfExists(target) }
            val temporary = withContext(Dispatchers.IO) {
                Files.createTempFile(objects, ".${entry.sha256}.", ".tmp")
            }
            try {
                val received = fetcher.fetch(
                    entry.sha256,
                    token,
                    temporary,
                    minOf(entry.size, AttachmentManifestEntry.MAX_ATTACHMENT_BYTES),
                )
                require(received == entry.size) { "attachment size/hash mismatch" }
                require(withContext(Dispatchers.IO) { verifyObject(temporary, entry, touch = false) }) {
                    "attachment size/hash mismatch"
                }
                withContext(Dispatchers.IO) {
                    FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                    publishAtomically(temporary, target)
                }
                verifiedBytes += entry.size
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(temporary) }
            }
        }

        val view = withContext(Dispatchers.IO) { materializeView(sessionId, manifest) }
        val protected = unique.mapTo(mutableSetOf()) { it.sha256 }
        val evictions = withContext(Dispatchers.IO) { evict(protected) }
        return AttachmentCacheResult(
            directory = view,
            stats = AttachmentCacheStats(hits, misses, verifiedBytes, evictions),
        )
    }

    suspend fun removeView(path: Path?) {
        if (path == null) return
        withContext(Dispatchers.IO) {
            val sessions = root.resolve("sessions").toAbsolutePath().normalize()
            val target = path.toAbsolutePath().normalize()
            require(target.parent == sessions) { "attachment view is outside the cache" }
            deleteTree(target)
        }
    }

    private fun verifyObject(
        path: Path,
        entry: AttachmentManifestEntry,
        touch: Boolean = true,
    ): Boolean = try {
        if (!Files.isRegularFile(path) || Files.size(path) != entry.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != entry.sha256) return false
        if (touch) Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()))
        true
    } catch (_: IOException) {
        false
    }

    private fun publishAtomically(temporary: Path, target: Path) {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun materializeView(
        sessionId: String,
        manifest: List<AttachmentManifestEntry>,
    ): Path {
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(64).ifBlank { "session" }
        val sessions = root.resolve("sessions")
        Files.createDirectories(sessions)
        val view = sessions.resolve(safeSession)
        deleteTree(view)
        Files.createDirectories(view)
        val used = mutableSetOf<String>()
        manifest.forEach { entry ->
            var name = entry.name
            if (!used.add(name)) {
                val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
                val stem = name.substring(0, dot)
                val suffix = name.substring(dot)
                val base = "$stem-${entry.sha256.take(8)}"
                name = "$base$suffix"
                var occurrence = 2
                while (!used.add(name)) {
                    name = "$base-$occurrence$suffix"
                    occurrence += 1
                }
            }
            val source = root.resolve("objects").resolve(entry.sha256)
            val target = view.resolve(name)
            try {
                Files.createLink(target, source)
            } catch (_: IOException) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: UnsupportedOperationException) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return view
    }

    private fun evict(protected: Set<String>): Int {
        val objects = root.resolve("objects")
        if (!Files.isDirectory(objects)) return 0
        val entries = Files.list(objects).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path) && SHA256.matches(path.name)
            }.map { path ->
                val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                CacheObject(path, attributes.size(), attributes.lastModifiedTime().toMillis())
            }.toList()
        }
        var total = entries.sumOf { it.size }
        var count = 0
        entries.sortedBy { it.lastUsed }.forEach { entry ->
            if (total <= maxCacheBytes) return@forEach
            if (entry.path.name in protected) return@forEach
            if (Files.deleteIfExists(entry.path)) {
                total -= entry.size
                count += 1
            }
        }
        return count
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private data class CacheObject(val path: Path, val size: Long, val lastUsed: Long)

    companion object {
        const val MAX_CACHE_BYTES = 512L * 1024 * 1024
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
