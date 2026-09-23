package org.upscalerelay.demux

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
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
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Private Range-capable HTTP bridge so mpv can reopen one SAF document. */
class LocalDocumentHttpServer(context: Context, private val uri: Uri) : Closeable {
    private val resolver = context.applicationContext.contentResolver
    private val closed = AtomicBoolean(false)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val slots = Semaphore(4)
    private val transfers = ConcurrentHashMap<Socket, Transfer>()
    private val workers = Collections.synchronizedSet(mutableSetOf<Thread>())
    private val mediaPath = "/media/${UUID.randomUUID()}"
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
                synchronized(clients) {
                    if (closed.get() || !slots.tryAcquire()) {
                        socket.close()
                    } else {
                        clients += socket
                        val worker = thread(start = false, name = "relay-local-http-client", isDaemon = true) {
                            try { serve(socket) } finally {
                                slots.release()
                                workers -= Thread.currentThread()
                            }
                        }
                        workers += worker
                        worker.start()
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) throw error
            }
        }
    }

    val url: String = "http://127.0.0.1:${server.localPort}$mediaPath"

    private fun serve(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                val request = reader.readBoundedLine()?.split(' ') ?: return
                if (request.size < 2 || request[0] !in setOf("GET", "HEAD")) {
                    respondError(client, 405, "Method Not Allowed")
                    return
                }
                if (request[1] != mediaPath) {
                    respondError(client, 404, "Not Found")
                    return
                }
                var rangeHeader: String? = null
                var headerBytes = 0
                while (true) {
                    val line = reader.readBoundedLine() ?: return
                    headerBytes += line.length + 2
                    require(headerBytes <= 16 * 1024) { "HTTP headers are too large" }
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
                }
                val range = try {
                    parseByteRange(rangeHeader, contentLength)
                } catch (_: IllegalArgumentException) {
                    respondError(client, 416, "Range Not Satisfiable", "Content-Range: bytes */$contentLength\r\n")
                    return
                }
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
                if (request[0] == "GET") copyRange(client, output, range)
                output.flush()
            } catch (_: Throwable) {
                // mpv routinely abandons speculative Range requests.
            } finally {
                clients -= client
            }
        }
    }

    private fun copyRange(client: Socket, output: java.io.OutputStream, range: ByteRange) {
        val transfer = Transfer()
        transfers[client] = transfer
        try {
            if (closed.get()) transfer.cancel()
            val asset = requireNotNull(resolver.openAssetFileDescriptor(uri, "r", transfer.cancellation))
            asset.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    transfer.input = input
                    transfer.cancellation.throwIfCanceled()
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
        } finally {
            transfers.remove(client)
            transfer.cancel()
        }
    }

    private class Transfer {
        val cancellation = CancellationSignal()
        @Volatile var input: FileInputStream? = null
        fun cancel() {
            cancellation.cancel()
            runCatching { input?.close() }
        }
    }

    private fun queryLength(): Long {
        val fromQuery = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        if (fromQuery != null && fromQuery > 0) return fromQuery
        return resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
    }

    private fun respondError(socket: Socket, code: Int, reason: String, extraHeaders: String = "") {
        socket.getOutputStream().write(
            "HTTP/1.1 $code $reason\r\n${extraHeaders}Connection: close\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
        transfers.values.forEach { it.cancel() }
        acceptThread.takeUnless { it === Thread.currentThread() }?.join(2_000)
        val deadline = System.nanoTime() + 2_000_000_000L
        synchronized(workers) { workers.toList() }.forEach { worker ->
            val millis = (deadline - System.nanoTime()) / 1_000_000
            if (millis > 0 && worker !== Thread.currentThread()) worker.join(millis)
        }
    }
}

internal data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

internal fun parseByteRange(header: String?, length: Long): ByteRange {
    require(length > 0)
    if (header == null) return ByteRange(0, length - 1)
    require(header.startsWith("bytes=")) { "unsupported Range header" }
    val spec = header.removePrefix("bytes=")
    require(',' !in spec) { "multiple ranges are unsupported" }
    val (startText, endText) = spec.split('-', limit = 2).let {
        require(it.size == 2) { "invalid Range header" }; it[0] to it[1]
    }
    val range = if (startText.isEmpty()) {
        val suffix = endText.toLong().coerceAtMost(length)
        ByteRange(length - suffix, length - 1)
    } else {
        val start = startText.toLong()
        val end = if (endText.isEmpty()) length - 1 else endText.toLong().coerceAtMost(length - 1)
        ByteRange(start, end)
    }
    require(range.start in 0 until length && range.endInclusive >= range.start) { "unsatisfiable Range" }
    return range
}

/** Limits allocation even when a local peer never terminates a header line. */
internal fun BufferedReader.readBoundedLine(maxChars: Int = 8192): String? {
    val line = StringBuilder()
    while (true) {
        val next = read()
        if (next < 0) return if (line.isEmpty()) null else line.toString()
        if (next == '\n'.code) return line.toString().removeSuffix("\r")
        require(line.length < maxChars) { "HTTP header line is too large" }
        line.append(next.toChar())
    }
}
