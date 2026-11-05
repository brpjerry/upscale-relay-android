package org.upscalerelay.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a demuxer reading /media through [HttpRangeReader] gets, against a local server. */
class HttpRangeReaderTest {
    private val file = ByteArray(1000) { (it * 7).toByte() }
    private val ranges = CopyOnWriteArrayList<String?>()
    private val encodings = CopyOnWriteArrayList<String?>()

    /** How the server answers: ranges as the relay's /media does, the whole file, or a status. */
    @Volatile private var mode = "ranges"
    private val stalled = CountDownLatch(1)
    private val released = CountDownLatch(1)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/media/") { exchange ->
            try {
                answer(exchange)
            } finally {
                exchange.close()
            }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/media/film.mkv"

    @After
    fun stop() {
        released.countDown()
        server.stop(0)
    }

    private fun answer(exchange: HttpExchange) {
        val range = exchange.requestHeaders.getFirst("Range")
        ranges += range
        encodings += exchange.requestHeaders.getFirst("Accept-Encoding")
        when (mode) {
            "missing" -> return exchange.sendResponseHeaders(404, -1)
            "whole" -> {
                exchange.sendResponseHeaders(200, file.size.toLong())
                exchange.responseBody.write(file)
                return
            }
            "stall" -> {
                stalled.countDown()
                released.await(10, TimeUnit.SECONDS)
                return exchange.sendResponseHeaders(503, -1)
            }
        }
        val (start, requestedEnd) = Regex("""bytes=(\d+)-(\d+)""").matchEntire(requireNotNull(range))!!
            .destructured.let { (a, b) -> a.toLong() to b.toLong() }
        if (start >= file.size) {
            exchange.responseHeaders.add("Content-Range", "bytes */${file.size}")
            return exchange.sendResponseHeaders(416, -1)
        }
        val end = minOf(requestedEnd, file.size - 1L)
        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${file.size}")
        exchange.sendResponseHeaders(206, end - start + 1)
        exchange.responseBody.write(file, start.toInt(), (end - start + 1).toInt())
    }

    private fun HttpRangeReader.read(position: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        val count = readAt(position, buffer, 0, length)
        return if (count < 0) ByteArray(0) else buffer.copyOf(count)
    }

    @Test
    fun `reads come from one chunk until they leave it, and skipped bytes are never fetched`() {
        HttpRangeReader(url, chunkBytes = 100).use { reader ->
            assertEquals(1000L, reader.size())
            assertArrayEquals(file.copyOfRange(0, 10), reader.read(0, 10))
            assertArrayEquals(file.copyOfRange(50, 100), reader.read(50, 80)) // to the chunk's end
            assertEquals(1, reader.requestCount)
            // A jump past a large element (a Matroska file's fonts).
            assertArrayEquals(file.copyOfRange(900, 950), reader.read(900, 50))
            assertEquals(listOf("bytes=0-99", "bytes=900-999"), ranges)
            assertEquals(200L, reader.bytesFetched)
            assertEquals(listOf("identity", "identity"), encodings)
        }
    }

    @Test
    fun `the end of the file reads as -1`() {
        HttpRangeReader(url, chunkBytes = 300).use { reader ->
            // Before any response has said how long the file is: the server's 416.
            assertEquals(-1, reader.readAt(5000, ByteArray(4), 0, 4))
            assertEquals(1000L, reader.size())
            assertArrayEquals(file.copyOfRange(990, 1000), reader.read(990, 100))
            assertEquals(-1, reader.readAt(1000, ByteArray(4), 0, 4))
            assertEquals(0, reader.readAt(10, ByteArray(4), 0, 0))
        }
    }

    @Test
    fun `a server that ignores Range serves only a read from the start`() {
        mode = "whole"
        HttpRangeReader(url, chunkBytes = 100).use { reader ->
            assertEquals(1000L, reader.size())
            assertArrayEquals(file.copyOfRange(0, 100), reader.read(0, 200))
            assertThrows(IOException::class.java) { reader.read(500, 10) }
        }
    }

    @Test
    fun `a missing file and a read past the byte limit fail`() {
        mode = "missing"
        HttpRangeReader(url).use { reader ->
            assertThrows(IOException::class.java) { reader.size() }
        }
        mode = "ranges"
        HttpRangeReader(url, chunkBytes = 100, maxBytes = 150).use { reader ->
            reader.read(0, 10)
            assertThrows(IOException::class.java) { reader.read(500, 10) }
        }
    }

    @Test(timeout = 10_000)
    fun `closing ends a read the server never answers, and every later one`() {
        mode = "stall"
        val reader = HttpRangeReader(url)
        var failure: Throwable? = null
        val reading = thread { failure = runCatching { reader.read(0, 10) }.exceptionOrNull() }
        assertTrue(stalled.await(5, TimeUnit.SECONDS))
        reader.close()
        reading.join(2_000)
        assertTrue("the read is still blocked", !reading.isAlive)
        assertTrue("$failure", failure is IOException)
        assertThrows(IOException::class.java) { reader.read(0, 10) }
    }

    @Test
    fun `closing ends reads the cached chunk could still have served`() {
        val reader = HttpRangeReader(url, chunkBytes = 100)
        assertEquals(1000L, reader.size())
        assertArrayEquals(file.copyOfRange(0, 10), reader.read(0, 10))
        reader.close()
        // Bytes 10..19 are in the chunk already fetched.
        assertThrows(IOException::class.java) { reader.read(10, 10) }
        assertThrows(IOException::class.java) { reader.size() }
        assertEquals(1, reader.requestCount)
    }

    @Test
    fun `Content-Range is read as the relay and the RFC write it`() {
        assertEquals(HttpRangeReader.ContentRange(0, 1000), HttpRangeReader.ContentRange.parse("bytes 0-99/1000"))
        assertEquals(HttpRangeReader.ContentRange(null, 1000), HttpRangeReader.ContentRange.parse("bytes */1000"))
        assertEquals(HttpRangeReader.ContentRange(5, null), HttpRangeReader.ContentRange.parse("bytes 5-9/*"))
        assertEquals(null, HttpRangeReader.ContentRange.parse("items 0-9/10"))
        assertEquals(null, HttpRangeReader.ContentRange.parse(null))
    }
}
