package org.upscalerelay.android

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** One child in a [FakeRelay] library directory. */
data class FakeEntry(val name: String, val directory: Boolean = false, val mtime: Long = 0)

/**
 * A relay on 127.0.0.1 that speaks just enough of the protocol for the client.
 *
 * The control WebSocket answers hello with capabilities, rejects every
 * open_session the way the server rejects one, and acknowledges teardown, so
 * a local open fails after its document bridge is up. With a [library] it
 * also serves GET /library the way the server does: directories first, then
 * name or newest-first order, and a cursor that is a bare offset into that
 * order. [libraryDelayMillis] holds a directory's response back.
 */
class FakeRelay(
    private val library: Map<String, List<FakeEntry>> = emptyMap(),
    private val libraryDelayMillis: (path: String) -> Long = { 0 },
) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val connections = CopyOnWriteArrayList<Socket>()
    val openRequests = AtomicInteger()

    /** "path|cursor|sort" for every library request, in arrival order. */
    val libraryRequests = CopyOnWriteArrayList<String>()
    val port: Int get() = server.localPort

    init {
        thread(name = "fake-relay", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections += socket
                thread(name = "fake-relay-connection", isDaemon = true) {
                    runCatching { socket.use(::serve) }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream()
        val target = input.readHeaderLine().split(' ').getOrElse(1) { "/" }
        var key = ""
        while (true) {
            val line = input.readHeaderLine()
            if (line.isEmpty()) break
            if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                key = line.substringAfter(':').trim()
            }
        }
        if (target.startsWith("/library")) {
            serveLibrary(output, Uri.parse("http://relay$target"))
            return
        }
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray()),
            Base64.NO_WRAP,
        )
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
        )
        output.flush()
        while (true) {
            val first = input.read()
            if (first < 0) return
            val second = input.read()
            var length = (second and 0x7f).toLong()
            if (length == 126L) {
                length = ((input.read() shl 8) or input.read()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
            val mask = if (second and 0x80 != 0) input.readExactly(4) else null
            val payload = input.readExactly(length.toInt())
            if (mask != null) {
                for (index in payload.indices) {
                    payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }
            when (first and 0x0f) {
                OPCODE_TEXT -> respond(output, JSONObject(String(payload)).optString("type"))
                OPCODE_PING -> frame(output, OPCODE_PONG, payload)
                OPCODE_CLOSE -> {
                    frame(output, OPCODE_CLOSE, payload)
                    return
                }
            }
        }
    }

    private fun serveLibrary(output: OutputStream, request: Uri) {
        val path = request.getQueryParameter("path").orEmpty()
        val cursor = request.getQueryParameter("cursor")
        val sort = request.getQueryParameter("sort")
        libraryRequests += "$path|${cursor.orEmpty()}|${sort.orEmpty()}"
        Thread.sleep(libraryDelayMillis(path))
        val entries = library[path]
        if (entries == null) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        val ordered = order(entries, sort)
        val offset = cursor?.toInt() ?: 0
        val limit = request.getQueryParameter("limit")?.toInt() ?: 100
        val children = JSONArray()
        ordered.drop(offset).take(limit).forEach { entry ->
            children.put(
                JSONObject()
                    .put("type", if (entry.directory) "directory" else "file")
                    .put("name", entry.name)
                    .put("path", if (path.isEmpty()) entry.name else "$path/${entry.name}"),
            )
        }
        val body = JSONObject()
            .put(
                "tree",
                JSONObject()
                    .put("type", "directory")
                    .put("name", path.substringAfterLast('/'))
                    .put("path", path)
                    .put("children", children),
            )
            .put("next_cursor", if (offset + limit < ordered.size) "${offset + limit}" else JSONObject.NULL)
            .toString()
            .toByteArray()
        output.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        output.write(body)
        output.flush()
    }

    /** The order the server lists a directory in for a sort key. */
    fun order(entries: List<FakeEntry>, sort: String?): List<FakeEntry> = when (sort) {
        "mtime" -> entries.sortedWith(
            compareByDescending<FakeEntry> { it.directory }
                .thenByDescending { it.mtime }
                .thenBy { it.name.lowercase() },
        )
        else -> entries.sortedWith(compareByDescending<FakeEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    private fun respond(output: OutputStream, type: String) {
        val reply = when (type) {
            "hello" -> capabilities()
            "open_session" -> {
                openRequests.incrementAndGet()
                """{"type":"error","code":"decode_error","message":"rejected by the audit test","fatal":false}"""
            }
            "teardown" -> """{"type":"closed"}"""
            else -> return
        }
        frame(output, OPCODE_TEXT, reply.toByteArray())
    }

    private fun capabilities(): String {
        val library = if (library.isEmpty()) {
            """"library":false"""
        } else {
            """"library":true,"library_sort":["name","mtime"]"""
        }
        return """{"type":"capabilities","protocol_version":1,"server_name":"audit-fake-relay",""" +
            """"models":[{"name":"passthrough","scale_factor":1}],""" +
            """"quality_tiers":["lossless-hevc","hevc-qp2","hevc-qp4","hevc-qp6",""" +
            """"hevc-qp10","hevc-qp14","hevc-qp18"],$library}"""
    }

    private fun frame(output: OutputStream, opcode: Int, payload: ByteArray) = synchronized(output) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size < 65_536 -> {
                output.write(126)
                output.write(payload.size shr 8)
                output.write(payload.size and 0xff)
            }
            else -> {
                output.write(127)
                for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() shr shift).toInt() and 0xff)
            }
        }
        output.write(payload)
        output.flush()
    }

    override fun close() {
        server.close()
        connections.forEach { runCatching { it.close() } }
    }

    private fun InputStream.readHeaderLine(): String {
        val line = StringBuilder()
        while (true) {
            val next = read()
            check(next >= 0) { "connection closed during the request head" }
            if (next == '\n'.code) return line.toString().trimEnd('\r')
            line.append(next.toChar())
        }
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            check(read >= 0) { "connection closed mid-frame" }
            offset += read
        }
        return bytes
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_TEXT = 0x1
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
    }
}
