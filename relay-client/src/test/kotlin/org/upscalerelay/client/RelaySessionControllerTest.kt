package org.upscalerelay.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.upscalerelay.protocol.DisplaySize
import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.MediaPacket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The controller composed with its real downlink, queue and loopback, against
 * a scripted control socket and a local media server. The player's connect
 * window is scaled down to two seconds, so "preparation slower than the
 * window" takes three seconds rather than a minute, with enough slack that
 * a busy CI runner cannot miss the window by accident.
 */
class RelaySessionControllerTest {
    private val display = DisplaySize(1920, 1080)
    private val token = "0123456789abcdef0123456789abcdef01"

    private companion object {
        const val CONNECT_WINDOW_MILLIS = 2_000L
        const val SLOWER_THAN_WINDOW_MILLIS = 3_000L
    }

    @Test(timeout = 20_000)
    fun `slow initial preparation leaves the player its whole connect window`() = runBlocking {
        val payload = byteArrayOf(1, 2, 3, 4)
        val media = FakeMedia(ackDelayMillis = SLOWER_THAN_WINDOW_MILLIS)
        val controller = controller(FakeControl(media.port))
        try {
            controller.connect(display)
            val endpoint = controller.preparePlayback("show.mkv", display)
            media.send(MediaPacket(payload, epoch = 0))
            // The loopback flushes its write buffer at end of stream.
            media.send(MediaPacket(ByteArray(0), flags = MediaFraming.FLAG_END_OF_STREAM, epoch = 0))
            // Preparation alone outlasted the window. The window belongs to
            // the player, so it starts only now.
            delay(150)
            assertNull(controller.failure.value)
            connect(endpoint).use { player ->
                assertArrayEquals(payload, player.getInputStream().readNBytes(payload.size))
            }
            assertNull(controller.failure.value)
        } finally {
            controller.close()
            media.close()
        }
    }

    @Test(timeout = 20_000)
    fun `a seek waiting on its acknowledgement does not run down the next loopback`() = runBlocking {
        val payload = byteArrayOf(9, 8, 7)
        val media = FakeMedia(ackDelayMillis = 0)
        val controller = controller(FakeControl(media.port, seekReplyDelayMillis = SLOWER_THAN_WINDOW_MILLIS))
        try {
            controller.connect(display)
            val first = controller.preparePlayback("show.mkv", display)
            val firstPlayer = connect(first)
            controller.startServerPlayback()
            controller.expectPlayerReload()
            val endpoint = controller.seek(targetPts = 90_000)
            firstPlayer.close()
            media.send(MediaPacket(payload, epoch = 1))
            // The loopback flushes its write buffer at end of stream.
            media.send(MediaPacket(ByteArray(0), flags = MediaFraming.FLAG_END_OF_STREAM, epoch = 1))
            delay(150)
            assertNull(controller.failure.value)
            connect(endpoint).use { player ->
                assertArrayEquals(payload, player.getInputStream().readNBytes(payload.size))
            }
            assertNull(controller.failure.value)
        } finally {
            controller.close()
            media.close()
        }
    }

    @Test(timeout = 20_000)
    fun `the session id is known from the open and outlives the session`() = runBlocking {
        val media = FakeMedia(ackDelayMillis = 0)
        val controller = controller(FakeControl(media.port))
        try {
            controller.connect(display)
            assertNull(controller.sessionId)
            controller.preparePlayback("show.mkv", display)
            assertEquals("s1", controller.sessionId)
            // Whoever asks a later connection about this session reads the id
            // from a controller that is already gone.
            controller.close()
            assertEquals("s1", controller.sessionId)
        } finally {
            controller.close()
            media.close()
        }
    }

    @Test(timeout = 20_000)
    fun `a player that never connects still fails the session`() = runBlocking {
        val media = FakeMedia(ackDelayMillis = 0)
        val controller = controller(FakeControl(media.port))
        try {
            controller.connect(display)
            controller.preparePlayback("show.mkv", display)
            val failure = withTimeout(CONNECT_WINDOW_MILLIS + 8_000) {
                while (controller.failure.value == null) delay(20)
                controller.failure.value
            }
            assertEquals(SocketTimeoutException::class.qualifiedName, failure?.exceptionType)
        } finally {
            controller.close()
            media.close()
        }
    }

    private fun controller(control: FakeControl) = RelaySessionController(
        host = "127.0.0.1",
        port = 8590,
        attachmentCacheRoot = null,
        webSocketFactory = control,
        playerConnectTimeoutMillis = CONNECT_WINDOW_MILLIS,
    )

    private fun connect(endpoint: PlaybackEndpoint): Socket {
        val uri = URI(endpoint.localUrl)
        return Socket(uri.host, uri.port).apply { soTimeout = 5_000 }
    }

    /** Answers hello, open_session and seek the way the relay server does. */
    private inner class FakeControl(
        private val mediaPort: Int,
        private val seekReplyDelayMillis: Long = 0,
    ) : WebSocket, WebSocket.Factory {
        private lateinit var listener: WebSocketListener
        private lateinit var request: Request
        @Volatile private var cancelled = false

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
            return this
        }

        private fun receive(message: String) = listener.onMessage(this, message)

        override fun send(text: String): Boolean {
            val message: JsonObject = Json.parseToJsonElement(text).jsonObject
            when (message["type"]?.jsonPrimitive?.content) {
                "hello" -> receive(
                    """{"type":"capabilities","protocol_version":1,"server_name":"test",""" +
                        """"models":[{"name":"passthrough","scale_factor":1}],""" +
                        """"quality_tiers":["lossless-hevc"],"library":false}""",
                )
                "open_session" -> receive(
                    """{"type":"session_opened","session_id":"s1","media_port":$mediaPort,""" +
                        """"downlink_token":"$token","downlink_codec":"hevc",""" +
                        """"downlink_container":"matroska","downlink_width":1920,""" +
                        """"downlink_height":1080,"epoch":0,"time_base":[1,1000],""" +
                        """"duration_s":600.0}""",
                )
                "seek" -> {
                    val epoch = message.getValue("epoch").jsonPrimitive.int
                    thread(isDaemon = true) {
                        Thread.sleep(seekReplyDelayMillis)
                        receive("""{"type":"seek_ready","epoch":$epoch}""")
                    }
                }
            }
            return !cancelled
        }

        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(bytes: ByteString): Boolean = !cancelled

        override fun close(code: Int, reason: String?): Boolean {
            cancelled = true
            return true
        }

        override fun cancel() {
            cancelled = true
        }
    }

    /** One downlink connection: acknowledges the handshake, then relays sent packets. */
    private inner class FakeMedia(ackDelayMillis: Long) : AutoCloseable {
        private val server = ServerSocket(0)
        private val outgoing = LinkedBlockingQueue<MediaPacket>()
        @Volatile private var socket: Socket? = null
        val port: Int = server.localPort

        init {
            thread(name = "relay-test-media", isDaemon = true) {
                runCatching {
                    val accepted = server.accept().also { socket = it }
                    val handshake = accepted.getInputStream().readNBytes(MediaFraming.HANDSHAKE_LENGTH)
                    check(
                        handshake.contentEquals(
                            MediaFraming.handshake(MediaFraming.DIRECTION_DOWNLINK, token),
                        ),
                    )
                    Thread.sleep(ackDelayMillis)
                    val output = accepted.getOutputStream()
                    output.write(0)
                    output.flush()
                    while (true) {
                        val packet = outgoing.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        output.write(MediaFraming.encode(packet))
                        output.flush()
                    }
                }
            }
        }

        fun send(packet: MediaPacket) = outgoing.put(packet)

        override fun close() {
            socket?.close()
            server.close()
        }
    }
}
