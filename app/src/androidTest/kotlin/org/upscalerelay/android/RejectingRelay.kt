package org.upscalerelay.android

import android.util.Base64
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

/**
 * A relay control endpoint on 127.0.0.1 that speaks just enough WebSocket for
 * the client: it answers hello with capabilities, rejects every open_session
 * the way the server rejects one, and acknowledges teardown. It serves no
 * library and no media, so a local open fails after its document bridge is up.
 */
class RejectingRelay : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val connections = CopyOnWriteArrayList<Socket>()
    val openRequests = AtomicInteger()
    val port: Int get() = server.localPort

    init {
        thread(name = "rejecting-relay", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections += socket
                thread(name = "rejecting-relay-connection", isDaemon = true) {
                    runCatching { socket.use(::serve) }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream()
        var key = ""
        while (true) {
            val line = input.readHeaderLine()
            if (line.isEmpty()) break
            if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                key = line.substringAfter(':').trim()
            }
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

    private fun respond(output: OutputStream, type: String) {
        val reply = when (type) {
            "hello" -> CAPABILITIES
            "open_session" -> {
                openRequests.incrementAndGet()
                """{"type":"error","code":"decode_error","message":"rejected by the audit test","fatal":false}"""
            }
            "teardown" -> """{"type":"closed"}"""
            else -> return
        }
        frame(output, OPCODE_TEXT, reply.toByteArray())
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
            check(next >= 0) { "connection closed during the upgrade" }
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
        const val CAPABILITIES =
            """{"type":"capabilities","protocol_version":1,"server_name":"audit-rejecting-relay",""" +
                """"models":[{"name":"passthrough","scale_factor":1}],""" +
                """"quality_tiers":["lossless-hevc","hevc-qp2","hevc-qp4","hevc-qp6",""" +
                """"hevc-qp10","hevc-qp14","hevc-qp18"],"library":false}"""
    }
}
