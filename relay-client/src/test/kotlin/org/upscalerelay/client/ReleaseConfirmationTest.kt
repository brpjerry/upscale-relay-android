package org.upscalerelay.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.upscalerelay.protocol.DisplaySize
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A session whose control connection died cannot acknowledge its teardown.
 * The next connection asks GET /status instead, and only the server saying
 * the session is gone, with no failed cleanup, lets a replacement open.
 */
class ReleaseConfirmationTest {
    private val asked = AtomicInteger()
    private var reply: (Int) -> Pair<Int, String> = { 200 to status() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/status") { exchange ->
            val (code, body) = reply(asked.incrementAndGet())
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val controller = RelaySessionController(
        host = "127.0.0.1",
        port = server.address.port,
        attachmentCacheRoot = null,
        webSocketFactory = HelloOnlyControl(),
        playerConnectTimeoutMillis = 1_000,
    )

    @After
    fun tearDown() {
        controller.close()
        server.stop(0)
    }

    @Test(timeout = 20_000)
    fun `a lost session is confirmed once the server stops listing it`() = runBlocking {
        // Still being torn down for the first two answers.
        reply = { call -> 200 to status(sessions = if (call <= 2) listOf("lost", "another") else listOf("another")) }
        controller.connect(DisplaySize(1920, 1080))
        controller.awaitReleased("lost", timeoutMillis = 10_000, pollMillis = 10)
        assertEquals(3, asked.get())
    }

    @Test(timeout = 20_000)
    fun `a session the server already released is confirmed at once`() = runBlocking {
        controller.connect(DisplaySize(1920, 1080))
        controller.awaitReleased("lost", timeoutMillis = 10_000, pollMillis = 5_000)
        assertEquals(1, asked.get())
    }

    @Test(timeout = 20_000)
    fun `a session the server still holds is never confirmed`() = runBlocking {
        reply = { 200 to status(sessions = listOf("lost")) }
        controller.connect(DisplaySize(1920, 1080))
        val refused = unconfirmed { controller.awaitReleased("lost", timeoutMillis = 200, pollMillis = 20) }
        assertTrue(refused.cause?.message, refused.cause?.message.orEmpty().contains("still holds session lost"))
        assertTrue("asked ${asked.get()} times", asked.get() > 1)
    }

    @Test(timeout = 20_000)
    fun `a failed native teardown is a restart, not a confirmation`() = runBlocking {
        // The server delists a session whose cleanup failed, so its absence
        // from the list means nothing once restart_required is set.
        reply = {
            200 to status(
                restartRequired = true,
                teardownError = """{"session_id":"lost","error":"RuntimeError('nvenc')","restart_required":true}""",
            )
        }
        controller.connect(DisplaySize(1920, 1080))
        try {
            controller.awaitReleased("lost", timeoutMillis = 10_000, pollMillis = 10)
            fail("a server that needs a restart confirmed a release")
        } catch (error: RelayServerException) {
            assertEquals("server_restart_required", error.code)
        }
    }

    @Test(timeout = 20_000)
    fun `a server that cannot say is not a confirmation`() = runBlocking {
        controller.connect(DisplaySize(1920, 1080))
        // No such endpoint, an answer that is not the status document, and
        // one that lists sessions without saying which.
        for (answer in listOf(404 to "Not Found", 200 to """{"server":"upscale-relay"}""", 200 to """{"sessions":[{}]}""")) {
            reply = { answer }
            unconfirmed { controller.awaitReleased("lost", timeoutMillis = 10_000, pollMillis = 10) }
        }
    }

    @Test(timeout = 20_000)
    fun `an unreachable server leaves the question open`() = runBlocking {
        controller.connect(DisplaySize(1920, 1080))
        server.stop(0)
        try {
            controller.awaitReleased("lost", timeoutMillis = 10_000, pollMillis = 10)
            fail("an unreachable server confirmed a release")
        } catch (error: IOException) {
            // Not the hard stop: nothing was learned, so the next connection asks again.
            assertFalse(error.toString(), error is TeardownUnconfirmedException)
            assertFalse(error.toString(), error is RelayServerException)
        }
    }

    private inline fun unconfirmed(block: () -> Unit): TeardownUnconfirmedException {
        try {
            block()
        } catch (error: TeardownUnconfirmedException) {
            return error
        }
        throw AssertionError("the release was confirmed")
    }

    /** The parts of the relay's /status document that the client reads, among ones it does not. */
    private fun status(
        sessions: List<String> = emptyList(),
        restartRequired: Boolean = false,
        teardownError: String = "null",
    ): String {
        val listed = sessions.joinToString(",") { """{"id":"$it","state":"playing","epoch":0,"pipeline":null}""" }
        return """{"server":"upscale-relay","protocol_version":1,"restart_required":$restartRequired,""" +
            """"native_teardown_error":$teardownError,"models":["passthrough"],"sessions":[$listed]}"""
    }

    /** A control socket that answers hello and nothing else. */
    private class HelloOnlyControl : WebSocket, WebSocket.Factory {
        private lateinit var listener: WebSocketListener
        private lateinit var request: Request

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            listener.onOpen(
                this,
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(101).message("Switching Protocols").build(),
            )
            return this
        }

        override fun send(text: String): Boolean {
            if (""""type":"hello"""" in text) {
                listener.onMessage(
                    this,
                    """{"type":"capabilities","protocol_version":1,"server_name":"test",""" +
                        """"models":[{"name":"passthrough","scale_factor":1}],""" +
                        """"quality_tiers":["lossless-hevc"],"library":false}""",
                )
            }
            return true
        }

        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }
}
