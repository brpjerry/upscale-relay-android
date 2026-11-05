package org.upscalerelay.client

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Random access to one HTTP resource through Range requests, for a demuxer
 * that reads a file's header from the server's /media ([SourceSizeProbe]).
 * Reads come from the last chunk fetched; a miss fetches [chunkBytes] from
 * the read position, so a demuxer that skips a large element (a Matroska
 * file's fonts) never downloads it. Each request has its own timeout and the
 * reader stops after [maxBytes] in all, so a demuxer that keeps scanning
 * cannot pull a whole film. [close] cancels the request in flight and fails
 * every later read, so a demuxer blocked on one returns promptly.
 */
class HttpRangeReader(
    private val url: String,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) : Closeable {
    init {
        require(chunkBytes > 0 && maxBytes > 0 && requestTimeoutMillis > 0)
    }

    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicReference<Call?>(null)
    private val lock = Any()
    private var chunkStart = 0L
    private var chunk = ByteArray(0)
    private var totalSize = -1L // unknown until a response says
    private var fetched = 0L
    private var requests = 0

    /** Bytes fetched so far. */
    val bytesFetched: Long get() = synchronized(lock) { fetched }

    /** Range requests made so far. */
    val requestCount: Int get() = synchronized(lock) { requests }

    /** The resource's length, read with the first chunk if no response has said yet; -1 if the server does not say. */
    fun size(): Long = synchronized(lock) {
        if (totalSize < 0 && requests == 0) fill(0)
        totalSize
    }

    /**
     * Copies up to [length] bytes at [position] into [buffer] and returns how
     * many; -1 at the end of the resource. Throws [IOException] for a failed
     * request, past [maxBytes], and once closed.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (length == 0) return 0
        if (totalSize in 0..position) return -1
        if (position < chunkStart || position >= chunkStart + chunk.size) {
            if (!fill(position)) return -1
        }
        val index = (position - chunkStart).toInt()
        val count = minOf(length, chunk.size - index)
        System.arraycopy(chunk, index, buffer, offset, count)
        count
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inFlight.getAndSet(null)?.cancel()
    }

    /** Fetches the chunk at [position]; false at the end of the resource. Holds [lock]. */
    private fun fill(position: Long): Boolean {
        if (closed.get()) throw IOException("the media reader is closed")
        val want = if (totalSize >= 0) minOf(chunkBytes.toLong(), totalSize - position) else chunkBytes.toLong()
        if (want <= 0) return false
        if (fetched + want > maxBytes) throw IOException("stopped reading the media after $maxBytes bytes")
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$position-${position + want - 1}")
            // Byte offsets are into the file itself, never a compressed body.
            .header("Accept-Encoding", "identity")
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
        inFlight.set(call)
        if (closed.get()) call.cancel() // closed between the check above and publishing the call
        requests += 1
        val bytes = try {
            call.execute().use { response ->
                when (response.code) {
                    HTTP_PARTIAL -> {
                        val range = ContentRange.parse(response.header("Content-Range"))
                            ?: throw IOException("a ranged reply without a usable Content-Range")
                        if (range.start != position) {
                            throw IOException("asked for byte $position, got a range from ${range.start}")
                        }
                        range.total?.let { totalSize = it }
                        response.body.source().readUpTo(want)
                    }
                    HTTP_OK -> {
                        // A server that ignores Range sends the file from its
                        // start, which serves only a read from the start.
                        if (position != 0L) throw IOException("the server does not serve byte ranges")
                        totalSize = response.body.contentLength()
                        response.body.source().readUpTo(want)
                    }
                    HTTP_RANGE_NOT_SATISFIABLE -> {
                        ContentRange.parse(response.header("Content-Range"))?.total?.let { totalSize = it }
                        ByteArray(0)
                    }
                    else -> throw IOException("media read failed with HTTP ${response.code}")
                }
            }
        } finally {
            inFlight.compareAndSet(call, null)
        }
        if (bytes.isEmpty()) return false
        chunkStart = position
        chunk = bytes
        fetched += bytes.size
        return true
    }

    private fun okio.BufferedSource.readUpTo(byteCount: Long): ByteArray {
        val sink = Buffer()
        while (sink.size < byteCount) {
            if (read(sink, byteCount - sink.size) == -1L) break
        }
        return sink.readByteArray()
    }

    /** `bytes <start>-<end>/<total>`, or `bytes *` + `/<total>` on a 416; total null when the server writes `*`. */
    internal data class ContentRange(val start: Long?, val total: Long?) {
        companion object {
            private val PATTERN = Regex("""bytes\s+(?:(\d+)-(\d+)|\*)/(\d+|\*)""", RegexOption.IGNORE_CASE)

            fun parse(header: String?): ContentRange? {
                val match = header?.trim()?.let(PATTERN::matchEntire) ?: return null
                val start = match.groupValues[1].takeIf(String::isNotEmpty)?.toLongOrNull()
                val total = match.groupValues[3].toLongOrNull()
                return ContentRange(start, total)
            }
        }
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 256 * 1024
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(DEFAULT_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(DEFAULT_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
