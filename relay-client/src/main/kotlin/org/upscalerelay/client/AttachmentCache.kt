package org.upscalerelay.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
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
    private val state = states.computeIfAbsent(root.toAbsolutePath().normalize()) { CacheState() }

    init { require(maxCacheBytes > 0) }

    suspend fun materialize(
        sessionId: String,
        manifest: List<AttachmentManifestEntry>,
        token: String,
    ): AttachmentCacheResult = state.mutex.withLock {
        require(token.isNotBlank()) { "cached attachment session omitted its token" }
        val objects = root.resolve("objects")
        require(manifest.all { entry ->
            SHA256.matches(entry.sha256) && entry.size in 0..AttachmentManifestEntry.MAX_ATTACHMENT_BYTES &&
                entry.name.isNotBlank() && entry.name.length <= AttachmentManifestEntry.MAX_NAME_LENGTH &&
                entry.name != "." && entry.name != ".." && '/' !in entry.name && '\\' !in entry.name
        }) { "invalid attachment manifest" }
        require(manifest.size <= 4096) { "too many attachments" }
        val unique = manifest.distinctBy { it.sha256 }
        require(manifest.sumOf { it.size } <= AttachmentManifestEntry.MAX_MANIFEST_BYTES) {
            "attachment manifest is too large"
        }
        require(manifest.groupBy { it.sha256 }.values.all { group -> group.map { it.size }.distinct().size == 1 }) {
            "conflicting attachment sizes"
        }
        val protected = state.views.values.flatten().toMutableSet().apply { addAll(unique.map { it.sha256 }) }
        var evictions = withContext(Dispatchers.IO) {
            Files.createDirectories(objects)
            if (!state.initialized) {
                // Views only live for this process. Reclaim crash leftovers once,
                // before any materialization can publish a new active view.
                deleteTree(root.resolve("sessions"))
                Files.list(objects).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".tmp") }.forEach(Files::deleteIfExists)
                }
                state.initialized = true
            }
            val required = unique.associate { it.sha256 to it.size }.toMutableMap()
            protected.forEach { digest ->
                if (digest !in required) required[digest] = Files.size(objects.resolve(digest))
            }
            val requiredBytes = required.values.sum()
            require(requiredBytes <= maxCacheBytes) { "active attachment views exceed cache capacity" }
            // Reserve room before downloads, including objects which are absent.
            val additionalBytes = unique.sumOf { entry ->
                val target = objects.resolve(entry.sha256)
                val existing = if (Files.isRegularFile(target)) Files.size(target) else 0L
                (entry.size - existing).coerceAtLeast(0)
            }
            evict(protected, maxCacheBytes - additionalBytes)
        }

        var hits = 0
        var misses = 0
        var verifiedBytes = 0L
        for (entry in unique) {
            val target = objects.resolve(entry.sha256)
            if (withContext(Dispatchers.IO) { verifyObject(target, entry) }) {
                hits += 1
                verifiedBytes += entry.size
                continue
            }
            misses += 1
            withContext(Dispatchers.IO) { Files.deleteIfExists(target) }
            var temporary: Path? = null
            try {
                withContext(Dispatchers.IO) {
                    temporary = Files.createTempFile(objects, ".${entry.sha256}.", ".tmp")
                }
                val download = requireNotNull(temporary)
                val received = fetcher.fetch(
                    entry.sha256,
                    token,
                    download,
                    minOf(entry.size, AttachmentManifestEntry.MAX_ATTACHMENT_BYTES),
                )
                require(received == entry.size) { "attachment size/hash mismatch" }
                require(withContext(Dispatchers.IO) { verifyObject(download, entry, touch = false) }) {
                    "attachment size/hash mismatch"
                }
                withContext(Dispatchers.IO) {
                    FileChannel.open(download, StandardOpenOption.WRITE).use { it.force(true) }
                    publishAtomically(download, target)
                }
                verifiedBytes += entry.size
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { temporary?.let(Files::deleteIfExists) }
            }
        }

        var view: Path? = null
        try {
            withContext(Dispatchers.IO) {
                view = materializeView(sessionId, manifest)
                evictions += evict(protected)
            }
            currentCoroutineContext().ensureActive()
            val published = requireNotNull(view)
            state.views[published.toAbsolutePath().normalize()] = unique.mapTo(mutableSetOf()) { it.sha256 }
            AttachmentCacheResult(
                directory = published,
                stats = AttachmentCacheStats(hits, misses, verifiedBytes, evictions),
            )
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { view?.let(::deleteTree) }
            throw error
        }
    }

    suspend fun removeView(path: Path?) {
        if (path == null) return
        state.mutex.withLock {
            withContext(Dispatchers.IO) {
                val sessions = root.resolve("sessions").toAbsolutePath().normalize()
                val target = path.toAbsolutePath().normalize()
                require(target.parent == sessions) { "attachment view is outside the cache" }
                deleteTree(target)
                state.views.remove(target)
                evict(state.views.values.flatten().toSet())
            }
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
        val view = Files.createTempDirectory(sessions, "$safeSession-")
        try {
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
        } catch (error: Throwable) {
            deleteTree(view)
            throw error
        }
    }

    private fun evict(protected: Set<String>, budget: Long = maxCacheBytes): Int {
        val objects = root.resolve("objects")
        if (!Files.isDirectory(objects)) return 0
        val entries = Files.list(objects).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path) && SHA256.matches(path.name)
            }.map { path ->
                val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                CacheObject(path, attributes.size(), attributes.lastModifiedTime().toMillis())
            }.iterator().asSequence().toList()
        }
        var total = entries.sumOf { it.size }
        var count = 0
        entries.sortedBy { it.lastUsed }.forEach { entry ->
            if (total <= budget) return@forEach
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

    private class CacheState {
        val mutex = Mutex()
        var initialized = false
        val views = mutableMapOf<Path, Set<String>>()
    }

    companion object {
        private val states = ConcurrentHashMap<Path, CacheState>()
        const val MAX_CACHE_BYTES = 512L * 1024 * 1024
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
