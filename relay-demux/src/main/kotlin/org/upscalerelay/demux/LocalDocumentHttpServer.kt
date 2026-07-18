package org.upscalerelay.demux

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Private Range-capable HTTP bridge so mpv can reopen one SAF document. */
class LocalDocumentHttpServer(context: Context, private val uri: Uri) : Closeable {
    private val resolver = context.applicationContext.contentResolver
    private val closed = AtomicBoolean(false)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val contentLength = queryLength().also {
        require(it > 0) { "The selected document does not expose a seekable length" }
    }
    private val contentType = resolver.getType(uri) ?: "application/octet-stream"
    private val server = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
    }
    private val acceptThread = thread(name = "relay-local-http", isDaemon = true) {
        while (!closed.get()) {
            try {
                val socket = server.accept()
                clients += socket
                thread(name = "relay-local-http-client", isDaemon = true) { serve(socket) }
            } catch (error: Throwable) {
                if (!closed.get()) throw error
            }
        }
    }

    val url: String = "http://127.0.0.1:${server.localPort}/media"

    private fun serve(socket: Socket) {
        socket.use { client ->
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                val request = reader.readLine()?.split(' ') ?: return
                if (request.size < 2 || request[0] !in setOf("GET", "HEAD")) {
                    respondError(client, 405, "Method Not Allowed")
                    return
                }
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
                }
                val range = parseByteRange(rangeHeader, contentLength)
                val partial = rangeHeader != null
                val output = client.getOutputStream().buffered(256 * 1024)
                val status = if (partial) "206 Partial Content" else "200 OK"
                val headers = buildString {
                    append("HTTP/1.1 $status\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: ${range.length}\r\n")
                    if (partial) append("Content-Range: bytes ${range.start}-${range.endInclusive}/$contentLength\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(headers.toByteArray(StandardCharsets.US_ASCII))
                if (request[0] == "GET") copyRange(output, range)
                output.flush()
            } catch (_: Throwable) {
                // mpv routinely abandons speculative Range requests.
            } finally {
                clients -= client
            }
        }
    }

    private fun copyRange(output: java.io.OutputStream, range: ByteRange) {
        val asset = requireNotNull(resolver.openAssetFileDescriptor(uri, "r"))
        asset.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                input.channel.position(descriptor.startOffset + range.start)
                var remaining = range.length
                val buffer = ByteArray(256 * 1024)
                while (remaining > 0 && !closed.get()) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    private fun queryLength(): Long {
        val fromQuery = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        if (fromQuery != null && fromQuery > 0) return fromQuery
        return resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
    }

    private fun respondError(socket: Socket, code: Int, reason: String) {
        socket.getOutputStream().write(
            "HTTP/1.1 $code $reason\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        synchronized(clients) { clients.toList().forEach(Socket::close) }
        acceptThread.takeUnless { it === Thread.currentThread() }?.join(2_000)
    }
}

internal data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

internal fun parseByteRange(header: String?, length: Long): ByteRange {
    require(length > 0)
    if (header == null) return ByteRange(0, length - 1)
    require(header.startsWith("bytes=")) { "unsupported Range header" }
    val spec = header.removePrefix("bytes=").substringBefore(',')
    val (startText, endText) = spec.split('-', limit = 2).let {
        require(it.size == 2) { "invalid Range header" }; it[0] to it[1]
    }
    val range = if (startText.isEmpty()) {
        val suffix = endText.toLong().coerceAtMost(length)
        ByteRange(length - suffix, length - 1)
    } else {
        val start = startText.toLong()
        ByteRange(start, endText.toLongOrNull()?.coerceAtMost(length - 1) ?: length - 1)
    }
    require(range.start in 0 until length && range.endInclusive >= range.start) { "unsatisfiable Range" }
    return range
}
