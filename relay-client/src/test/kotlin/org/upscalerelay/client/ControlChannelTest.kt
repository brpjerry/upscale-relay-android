package org.upscalerelay.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.upscalerelay.protocol.DisplaySize
import java.io.IOException
import java.nio.file.Path

class ControlChannelTest {
    @Test
    fun `malformed bearer tokens fail without exposing the secret in exception text`() = runBlocking {
        val control = ControlChannel("127.0.0.1", 8590, {})
        try {
            val secret = "secret-bearer\nheader"
            val error = runCatching {
                control.fetchAttachment("a".repeat(64), secret, Path.of("unused"), 1)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(requireNotNull(error).stackTraceToString().contains("secret-bearer"))
        } finally {
            control.close()
        }
    }

    @Test
    fun `teardown waits for closed acknowledgement and ignores state closed`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket)
        open(control)
        val teardown = async(start = CoroutineStart.UNDISPATCHED) { control.teardown() }
        assertEquals("teardown", socket.sent.last()["type"]?.jsonPrimitive?.content)
        socket.receive("""{"type":"state","state":"closed"}""")
        yield()
        assertFalse(teardown.isCompleted)
        assertFalse(socket.cancelled)
        socket.receive("""{"type":"closed"}""")
        withTimeout(2_000) { teardown.await() }
        assertTrue(socket.cancelled)
        val count = socket.sent.size
        control.teardown()
        assertEquals(count, socket.sent.size)
    }

    @Test
    fun `EOF without acknowledgement is not successful cleanup`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket)
        open(control)
        val teardown = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { control.teardown() }.exceptionOrNull()
        }
        socket.listener.onClosed(socket, 1000, "")
        val error = withTimeout(2_000) { teardown.await() }
        assertTrue(error is TeardownUnconfirmedException)
        assertEquals(FailureKind.TEARDOWN_UNCONFIRMED, classifyFailure(requireNotNull(error)))
        assertFalse(classifyFailure(error).recoverable)
        assertTrue(socket.cancelled)
    }

    @Test
    fun `silent teardown is bounded and closes local socket`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket, timeout = 25)
        open(control)
        val error = withTimeout(2_000) { runCatching { control.teardown() }.exceptionOrNull() }
        assertTrue(error is TeardownUnconfirmedException)
        assertTrue(socket.cancelled)
    }

    @Test
    fun `parent timeout remains cancellation and still closes the socket`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket)
        open(control)
        assertNull(withTimeoutOrNull(25) { control.teardown(); true })
        assertTrue(socket.cancelled)
        assertTrue(runCatching { control.teardown() }.exceptionOrNull() is TeardownUnconfirmedException)
    }

    @Test
    fun `native teardown failure requires server restart without retries`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket)
        open(control)
        val teardown = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { control.teardown() }.exceptionOrNull()
        }
        socket.listener.onClosing(socket, 1011, "native teardown incomplete; server restart required")
        val error = requireNotNull(withTimeout(2_000) { teardown.await() })
        assertEquals(FailureKind.SERVER_RESTART_REQUIRED, classifyFailure(error))
        assertFalse(classifyFailure(error).recoverable)
    }

    @Test
    fun `browse connection needs no session teardown barrier`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket)
        control.teardown()
        assertTrue(socket.cancelled)
        assertFalse(socket.sent.any { it["type"]?.jsonPrimitive?.content == "teardown" })
    }

    @Test
    fun `stale seek ready is ignored and progress remains available after ready`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket)
        val progress = mutableListOf<Int>()
        control.onSeekProgress = { progress += it.epoch }
        val seek = async(start = CoroutineStart.UNDISPATCHED) { control.seek(15_000, 2) }
        socket.receive("""{"type":"seek_ready","epoch":1}""")
        yield()
        assertFalse(seek.isCompleted)
        socket.receive("""{"type":"seek_ready","epoch":2}""")
        withTimeout(2_000) { seek.await() }
        socket.receive("""{"type":"seek_progress","epoch":2,"target_pts":15000,"stage":"future","message":null}""")
        assertEquals(listOf(2), progress)
        control.close()
    }

    @Test
    fun `malformed replies fail waiters instead of escaping reader callback`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket)
        val seek = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { control.seek(1, 1) }.exceptionOrNull()
        }
        socket.receive("""{"type":"seek_ready","epoch":{}}""")
        assertTrue(withTimeout(2_000) { seek.await() } is IOException)
        control.close()
    }

    private suspend fun connected(socket: FakeSocket, timeout: Long = 5_000): ControlChannel =
        ControlChannel("127.0.0.1", 8590, {}, socket, timeout).also {
            it.connect(DisplaySize(1920, 1080))
        }

    private suspend fun open(control: ControlChannel) {
        control.openSession("clip.mkv", "passthrough", DisplaySize(1920, 1080),
            "lossless-hevc", "fit", null, false, false)
    }

    private class FakeSocket : WebSocket, WebSocket.Factory {
        lateinit var listener: WebSocketListener
        private lateinit var request: Request
        var cancelled = false
        val sent = mutableListOf<JsonObject>()

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
            return this
        }

        fun receive(message: String) = listener.onMessage(this, message)
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            val message = Json.parseToJsonElement(text).jsonObject
            sent += message
            when (message["type"]?.jsonPrimitive?.content) {
                "hello" -> receive("""{"type":"capabilities","protocol_version":1,"server_name":"test","models":[],"quality_tiers":[]}""")
                "open_session" -> receive("""{"type":"session_opened"}""")
            }
            return !cancelled
        }
        override fun send(bytes: ByteString): Boolean = error("unexpected binary send")
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() { cancelled = true }
    }
}
