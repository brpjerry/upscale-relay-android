package org.upscalerelay.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.upscalerelay.protocol.DisplaySize
import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.MediaPacket
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * PROTOCOL.md §1.2 (a session kept through a lost control connection) and
 * §5 (the buffer target), against a scripted server that hands out a fresh
 * control connection per connect, and a real downlink.
 */
class SessionResumeTest {
    private val display = DisplaySize(1920, 1080)
    private val token = "0123456789abcdef0123456789abcdef01"
    private val media = FakeMedia()
    private val server = FakeServer(media.port)
    private val statusAsked = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var listedSessions: List<String> = emptyList()
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/status") { exchange ->
            statusAsked.incrementAndGet()
            val listed = listedSessions.joinToString(",") { """{"id":"$it","state":"playing","epoch":0}""" }
            val bytes = """{"restart_required":false,"sessions":[$listed]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val controller = RelaySessionController(
        host = "127.0.0.1",
        port = http.address.port,
        attachmentCacheRoot = null,
        webSocketFactory = server,
        playerConnectTimeoutMillis = 10_000,
        serverHeartbeatWindowMillis = 500,
    )

    @After
    fun tearDown() {
        controller.close()
        media.close()
        http.stop(0)
    }

    private suspend fun playing(bufferTargetMillis: Long? = null): PlaybackEndpoint {
        controller.connect(display)
        val endpoint = controller.preparePlayback("show.mkv", display, bufferTargetMillis = bufferTargetMillis)
        controller.startServerPlayback()
        return endpoint
    }

    /**
     * Waits for the resume to reach the server and finish. The flag alone
     * could have gone true and false before anything subscribed to it.
     */
    private suspend fun awaitResumed(resumes: Int = 1) = withTimeout(10_000) {
        while (server.allSent("resume_session").size < resumes) delay(10)
        controller.resuming.first { !it }
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a session opens for a resume and with its buffer target only as advertised`() = runBlocking {
        playing(bufferTargetMillis = 900)
        val open = server.sent("open_session")
        assertEquals(true, open["resume"]?.jsonPrimitive?.booleanOrNull)
        // Sent as the server applies it: raised to its 2 s floor.
        assertEquals(2_000L, open["buffer_target_ms"]?.jsonPrimitive?.longOrNull)
        assertNotNull(controller.resumeToken)
        assertEquals("srv", controller.sessionServerId)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `an older server is asked for neither`() = runBlocking {
        server.sessionResume = false
        playing()
        val open = server.sent("open_session")
        assertFalse("resume" in open)
        assertFalse("buffer_target_ms" in open)
        assertNull(controller.resumeToken)
        // Without a resume, a lost connection ends the session as before.
        server.connections.last().drop()
        withTimeout(5_000) { controller.failure.first { it != null } }
        assertEquals(1, server.connections.size)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `buffer reports carry the target the player sizes`() = runBlocking {
        playing()
        controller.updatePlayerBuffer(PlayerBufferSnapshot(5_000, 40_000_000, bufferTargetMillis = 150_000))
        withTimeout(5_000) {
            while (server.allSent("buffer_report").none { it["buffer_target_ms"]?.jsonPrimitive?.longOrNull == 150_000L }) {
                delay(50)
            }
        }
        controller.updatePlayerBuffer(PlayerBufferSnapshot(5_000, 40_000_000, bufferTargetMillis = null))
        val before = server.allSent("buffer_report").size
        withTimeout(5_000) { while (server.allSent("buffer_report").size <= before + 1) delay(50) }
        // No target: the report leaves the session's as it is.
        assertFalse("buffer_target_ms" in server.allSent("buffer_report").last())
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a lost connection is resumed and the downlink plays on`() = runBlocking {
        val endpoint = playing()
        val player = connect(endpoint)
        server.connections.last().drop()
        awaitResumed()
        assertNull(controller.failure.value)
        val resume = server.sent("resume_session")
        assertEquals("s1", resume["session_id"]?.jsonPrimitive?.content)
        assertEquals(server.resumeToken, resume["resume_token"]?.jsonPrimitive?.content)
        // The same media connection carries on.
        val payload = byteArrayOf(5, 6, 7)
        media.send(MediaPacket(payload, epoch = 0))
        media.send(MediaPacket(ByteArray(0), flags = MediaFraming.FLAG_END_OF_STREAM, epoch = 0))
        player.use { assertTrue(payload.contentEquals(it.getInputStream().readNBytes(payload.size))) }
        // Reports go on over the resumed connection.
        val resumedConnection = server.connections.last()
        withTimeout(5_000) { while (resumedConnection.sent.none { it.type == "buffer_report" }) delay(50) }
        assertNull(controller.failure.value)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a connection lost again just after its resume is resumed again`() = runBlocking {
        playing()
        // The controller retires the original once the replacement is
        // installed, while that resume is still finishing: the replacement
        // drops right then.
        val original = server.connections.last()
        original.onCancel = { server.connections.last().drop() }
        original.drop()
        awaitResumed(resumes = 2)
        assertNull(controller.failure.value)
        assertEquals(2, server.allSent("resume_session").size)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `attempts go on through a dropout and stop once one gets through`() = runBlocking {
        playing()
        server.refuseConnections = true
        server.connections.last().drop()
        withTimeout(5_000) { controller.resuming.first { it } }
        delay(1_200)
        assertTrue(controller.resuming.value)
        val refused = server.connections.size
        server.refuseConnections = false
        controller.networkAvailable()
        awaitResumed()
        assertTrue("attempts were made during the dropout: $refused", refused > 2)
        assertNull(controller.failure.value)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a seek the server never received is sent again after the resume`() = runBlocking {
        playing()
        controller.markRendering()
        server.loseSeeks = true
        val seeking = async { controller.seek(targetPts = 90_000) }
        withTimeout(5_000) { while (!server.sentAny("seek")) delay(20) }
        server.loseSeeks = false
        server.connections.last().drop()
        val endpoint = withTimeout(10_000) { seeking.await() }
        assertEquals(1, endpoint.session.epoch)
        assertEquals(2, server.allSent("seek").size)
        assertEquals(1, server.epoch)
        assertNull(controller.failure.value)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a seek whose reply was lost is answered by the resume, not sent again`() = runBlocking {
        playing()
        controller.markRendering()
        server.answerSeeks = false
        val seeking = async { controller.seek(targetPts = 90_000) }
        withTimeout(5_000) { while (server.epoch != 1) delay(20) }
        server.answerSeeks = true
        server.connections.last().drop()
        val endpoint = withTimeout(10_000) { seeking.await() }
        assertEquals(1, endpoint.session.epoch)
        assertEquals(1, server.allSent("seek").size)
        assertNull(controller.failure.value)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a pause made during the dropout reaches the resumed session`() = runBlocking {
        playing()
        controller.markRendering()
        server.refuseConnections = true
        server.connections.last().drop()
        withTimeout(5_000) { controller.resuming.first { it } }
        controller.setPaused(true)
        assertEquals(SessionState.PAUSED, controller.state.value)
        server.refuseConnections = false
        controller.networkAvailable()
        awaitResumed()
        withTimeout(5_000) { while (server.state != "paused") delay(20) }
        assertNull(controller.failure.value)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a refused resume fails the session as a lost connection`() = runBlocking {
        playing()
        server.forgetSession = true
        server.connections.last().drop()
        val failure = withTimeout(10_000) { controller.failure.first { it != null } }!!
        assertTrue(failure.summary, failure.summary.contains("could not be resumed"))
        // Recoverable: the app opens a fresh session, as it always did.
        assertTrue(failure.kind.recoverable)
        assertFalse(controller.resuming.value)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `the token goes to no other server and no server that stopped resuming`() = runBlocking {
        for (change in listOf<FakeServer.() -> Unit>({ serverId = "another" }, { sessionResume = false })) {
            val fresh = SessionResumeTest()
            try {
                runBlocking {
                    fresh.playing()
                    fresh.server.change()
                    fresh.server.connections.last().drop()
                    withTimeout(10_000) { fresh.controller.failure.first { it != null } }
                    assertFalse(fresh.server.sentAny("resume_session"))
                }
            } finally {
                fresh.tearDown()
            }
        }
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a downlink lost in the dropout ends playback`() = runBlocking {
        playing()
        server.downlinkAttached = false
        server.connections.last().drop()
        val failure = withTimeout(10_000) { controller.failure.first { it != null } }!!
        assertTrue(failure.summary, failure.summary.contains("media connection"))
        assertTrue(failure.kind.recoverable)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a session another connection took over is not taken back or released`() = runBlocking {
        playing()
        server.connections.last().takeOver()
        val failure = withTimeout(10_000) { controller.failure.first { it != null } }!!
        assertEquals(FailureKind.SERVER_REJECTED, failure.kind)
        assertFalse(server.sentAny("resume_session"))
        assertNull(controller.resumeToken)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a teardown during the resume is unconfirmed and leaves the token for the next connection`() = runBlocking {
        playing()
        server.refuseConnections = true
        server.connections.last().drop()
        withTimeout(5_000) { controller.resuming.first { it } }
        try {
            controller.teardown()
            fail("a teardown over a lost connection was confirmed")
        } catch (expected: TeardownUnconfirmedException) {
        }
        assertFalse(controller.resuming.value)
        assertEquals("s1", controller.sessionId)
        assertEquals(server.resumeToken, controller.resumeToken)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a held session is taken over and torn down before the release is polled`() = runBlocking {
        controller.connect(display)
        listedSessions = listOf("held")
        server.onTeardown = { listedSessions = emptyList() }
        val browse = server.connections.last()
        controller.awaitReleased("held", timeoutMillis = 5_000, pollMillis = 20, resumeToken = server.resumeToken, issuerServerId = "srv")
        val releasing = server.connections.last()
        // On a connection of its own: the browse connection stays usable.
        assertTrue(releasing !== browse)
        assertEquals(listOf("hello", "resume_session", "teardown"), releasing.sent.map { it.type })
        assertEquals("held", releasing.sent[1].message["session_id"]?.jsonPrimitive?.content)
        assertEquals(1, statusAsked.get())
        assertTrue(browse.open)
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a refused takeover leaves the verdict to the status poll`() = runBlocking {
        controller.connect(display)
        server.forgetSession = true
        listedSessions = emptyList()
        controller.awaitReleased("gone", timeoutMillis = 5_000, pollMillis = 20, resumeToken = "stale", issuerServerId = "srv")
        assertFalse(server.sentAny("teardown"))
        assertEquals(1, statusAsked.get())
    }

    // Bugs caught: 0, False positives: 0
    @Test(timeout = 20_000)
    fun `a token from another server's session is never sent`() = runBlocking {
        controller.connect(display)
        controller.awaitReleased("gone", timeoutMillis = 5_000, pollMillis = 20, resumeToken = "theirs", issuerServerId = "another")
        assertFalse(server.sentAny("resume_session"))
    }

    private fun connect(endpoint: PlaybackEndpoint): Socket {
        val uri = URI(endpoint.localUrl)
        return Socket(uri.host, uri.port).apply { soTimeout = 5_000 }
    }

    private class Sent(val message: JsonObject) {
        val type: String? = message["type"]?.jsonPrimitive?.content
    }

    /** The relay server's control side, one [Connection] per connect. */
    private inner class FakeServer(private val mediaPort: Int) : WebSocket.Factory {
        val connections = CopyOnWriteArrayList<Connection>()
        val resumeToken = "resume-secret"
        @Volatile var sessionResume = true
        @Volatile var serverId: String? = "srv"
        @Volatile var refuseConnections = false
        @Volatile var loseSeeks = false
        @Volatile var answerSeeks = true
        @Volatile var forgetSession = false
        @Volatile var downlinkAttached = true
        @Volatile var epoch = 0
        @Volatile var state = "open"
        @Volatile var onTeardown: () -> Unit = {}

        fun allSent(type: String): List<JsonObject> =
            connections.flatMap { it.sent }.filter { it.type == type }.map { it.message }

        fun sent(type: String): JsonObject = allSent(type).last()
        fun sentAny(type: String): Boolean = allSent(type).isNotEmpty()

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val connection = Connection(request, listener)
            connections += connection
            if (refuseConnections) {
                thread(isDaemon = true) {
                    Thread.sleep(20)
                    connection.open = false
                    listener.onFailure(connection, ConnectException("Connection refused"), null)
                }
            } else {
                listener.onOpen(connection, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(101).message("Switching Protocols").build())
            }
            return connection
        }

        inner class Connection(private val request: Request, private val listener: WebSocketListener) : WebSocket {
            val sent = CopyOnWriteArrayList<Sent>()
            @Volatile var open = true
            @Volatile var onCancel: () -> Unit = {}

            private fun reply(text: String) = listener.onMessage(this, text)

            /** The network goes: the client's heartbeat fails the socket. */
            fun drop() {
                open = false
                listener.onFailure(this, IOException("sent ping but didn't receive pong"), null)
            }

            /** The server hands this connection's session to another one (§1.2: code 4001). */
            fun takeOver() {
                open = false
                listener.onClosing(this, CLOSE_SESSION_TAKEN_OVER, "session resumed elsewhere")
            }

            override fun send(text: String): Boolean {
                if (!open) return false
                val message = Json.parseToJsonElement(text).jsonObject
                sent += Sent(message)
                when (message["type"]?.jsonPrimitive?.content) {
                    "hello" -> reply(
                        """{"type":"capabilities","protocol_version":1,"server_name":"test",""" +
                            """"models":[{"name":"passthrough","scale_factor":1}],""" +
                            """"quality_tiers":["lossless-hevc"],"library":false""" +
                            (if (sessionResume) ""","session_resume":true""" else "") +
                            (serverId?.let { ""","server_id":"$it"""" } ?: "") + "}",
                    )
                    "open_session" -> {
                        val resume = message["resume"]?.jsonPrimitive?.booleanOrNull == true
                        val target = message["buffer_target_ms"]?.jsonPrimitive?.longOrNull
                        reply(
                            """{"type":"session_opened","session_id":"s1","media_port":$mediaPort,""" +
                                """"downlink_token":"$token","downlink_codec":"hevc",""" +
                                """"downlink_container":"matroska","downlink_width":1920,""" +
                                """"downlink_height":1080,"epoch":0,"time_base":[1,1000]""" +
                                (target?.let { ""","buffer_target_ms":$it""" } ?: "") +
                                (if (resume) ""","resume_token":"$resumeToken","resume_grace_s":120.0""" else "") +
                                "}",
                        )
                    }
                    "play" -> state = "playing"
                    "pause" -> state = "paused"
                    "seek" -> {
                        if (loseSeeks) return true
                        val seekEpoch = message.getValue("epoch").jsonPrimitive.int
                        if (seekEpoch > epoch) epoch = seekEpoch
                        if (answerSeeks) thread(isDaemon = true) { reply("""{"type":"seek_ready","epoch":$seekEpoch}""") }
                    }
                    "resume_session" -> {
                        val ok = !forgetSession && message["resume_token"]?.jsonPrimitive?.content == resumeToken
                        reply(
                            if (ok) {
                                """{"type":"session_resumed","session_id":"${message["session_id"]?.jsonPrimitive?.content}",""" +
                                    """"state":"$state","epoch":$epoch,"uplink_attached":false,""" +
                                    """"downlink_attached":$downlinkAttached,"buffer_target_ms":10000}"""
                            } else {
                                """{"type":"error","code":"resume_failed","message":"no such session","fatal":false}"""
                            },
                        )
                    }
                    "teardown" -> {
                        onTeardown()
                        reply("""{"type":"closed"}""")
                    }
                }
                return true
            }

            override fun request(): Request = request
            override fun queueSize(): Long = 0
            override fun send(bytes: ByteString): Boolean = open
            override fun close(code: Int, reason: String?): Boolean {
                open = false
                return true
            }

            override fun cancel() {
                open = false
                onCancel()
            }
        }
    }

    /** One downlink connection: acknowledges the handshake, then relays sent packets. */
    private inner class FakeMedia : AutoCloseable {
        private val server = ServerSocket(0)
        private val outgoing = LinkedBlockingQueue<MediaPacket>()
        @Volatile private var socket: Socket? = null
        val port: Int = server.localPort

        init {
            thread(name = "relay-test-media", isDaemon = true) {
                runCatching {
                    val accepted = server.accept().also { socket = it }
                    if (server.isClosed) {
                        accepted.close()
                        return@runCatching
                    }
                    accepted.getInputStream().readNBytes(MediaFraming.HANDSHAKE_LENGTH)
                    val output = accepted.getOutputStream()
                    output.write(0)
                    output.flush()
                    while (!server.isClosed) {
                        val packet = outgoing.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        output.write(MediaFraming.encode(packet))
                        output.flush()
                    }
                }
            }
        }

        fun send(packet: MediaPacket) = outgoing.put(packet)

        override fun close() {
            server.close()
            socket?.close()
        }
    }
}
