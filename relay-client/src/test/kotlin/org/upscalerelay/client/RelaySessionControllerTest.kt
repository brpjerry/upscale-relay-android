package org.upscalerelay.client

import kotlinx.coroutines.awaitCancellation
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.DisplaySize
import org.upscalerelay.protocol.DisplayTarget
import org.upscalerelay.protocol.HighBitDepth
import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.MediaPacket
import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.VideoColor
import org.upscalerelay.protocol.VideoSize
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
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

    private fun controller(control: FakeControl, probeTimeoutMillis: Long = 10_000) = RelaySessionController(
        host = "127.0.0.1",
        port = 8590,
        attachmentCacheRoot = null,
        webSocketFactory = control,
        playerConnectTimeoutMillis = CONNECT_WINDOW_MILLIS,
        sourceSizeProbeTimeoutMillis = probeTimeoutMillis,
    )

    private fun connect(endpoint: PlaybackEndpoint): Socket {
        val uri = URI(endpoint.localUrl)
        return Socket(uri.host, uri.port).apply { soTimeout = 5_000 }
    }

    @Test(timeout = 20_000)
    fun `deep colour and the display are asked for only as the server advertises them`() = runBlocking {
        suspend fun session(
            capabilities: String,
            opened: String = "",
            seekReady: String = "",
            seekTarget: DisplayTarget? = null,
        ): FakeControl {
            val media = FakeMedia(ackDelayMillis = 0)
            val control = FakeControl(media.port, capabilities = capabilities, opened = opened, seekReady = seekReady)
            val controller = controller(control)
            try {
                controller.connect(display)
                val first = controller.preparePlayback(
                    "show.mkv", display,
                    highBitDepth = HighBitDepth.HDR, displayTarget = DisplayTarget.SDR,
                )
                connect(first).use {
                    controller.startServerPlayback()
                    controller.expectPlayerReload()
                    control.lastSeekSession = controller.seek(targetPts = 90_000, displayTarget = seekTarget).session
                }
            } finally {
                controller.close()
                media.close()
            }
            return control
        }
        val sdr = Json.parseToJsonElement("""{"transfer":"sdr"}""")

        val mapping = session(
            capabilities = ""","high_bit_depth":true,"display_mapping":true""",
            opened = ""","downlink_bit_depth":10,"downlink_color":{"primaries":1,"transfer":1,"matrix":1},""" +
                """"downlink_mapping":{"transfer":"sdr","applied":["dolby_vision"]}""",
            seekReady = ""","downlink_color":{"primaries":9,"transfer":16,"matrix":9},""" +
                """"downlink_mapping":{"transfer":"sdr","applied":[]}""",
        )
        assertEquals("hdr", mapping.sent("open_session")["high_bit_depth"]?.jsonPrimitive?.content)
        assertEquals(sdr, mapping.sent("open_session")["display_target"])
        assertEquals(sdr, mapping.sent("seek")["display_target"])
        // The epoch's endpoint describes the epoch seek_ready announced.
        val seeked = requireNotNull(mapping.lastSeekSession)
        assertEquals(1, seeked.epoch)
        assertEquals(VideoColor(9, 16, 9), seeked.downlinkColor)
        assertEquals(emptyList<String>(), seeked.downlinkMapping?.applied)
        assertEquals(10, seeked.downlinkBitDepth)

        val deepOnly = session(capabilities = ""","high_bit_depth":true""")
        assertEquals("hdr", deepOnly.sent("open_session")["high_bit_depth"]?.jsonPrimitive?.content)
        assertNull(deepOnly.sent("open_session")["display_target"])
        assertNull(deepOnly.sent("seek")["display_target"])

        // A seek after the display changed names the new one, and the server
        // remaps from that epoch on. A session that named none names none.
        val pq = DisplayTarget("pq", 540.0)
        val remapped = session(
            capabilities = ""","high_bit_depth":true,"display_mapping":true""",
            seekTarget = pq,
        )
        assertEquals(sdr, remapped.sent("open_session")["display_target"])
        assertEquals(pq.toJson(), remapped.sent("seek")["display_target"])
        assertNull(session(capabilities = ""","high_bit_depth":true""", seekTarget = pq).sent("seek")["display_target"])

        // A server from before either field streams 8 bits, unasked.
        val older = session(capabilities = "")
        assertNull(older.sent("open_session")["high_bit_depth"])
        assertNull(older.sent("open_session")["display_target"])
        assertNull(older.sent("seek")["display_target"])
        assertEquals(8, older.lastSeekSession?.downlinkBitDepth)
    }

    private val twoModels = """[{"name":"passthrough","scale_factor":1},{"name":"2x_Model","scale_factor":2}]"""

    /** What a passthrough4k open sent, read and returned, after one seek. */
    private class Opened(
        val control: FakeControl,
        val endpoint: PlaybackEndpoint,
        val seeked: PlaybackEndpoint,
        val probed: List<String>,
    )

    private suspend fun openLibraryFile(
        passthrough4k: Boolean = true,
        model: String = "2x_Model",
        known: VideoSize? = null,
        probe: suspend () -> VideoSize?,
    ): Opened {
        val media = FakeMedia(ackDelayMillis = 0)
        val control = FakeControl(media.port, models = twoModels)
        val controller = controller(control, probeTimeoutMillis = 500)
        val probed = CopyOnWriteArrayList<String>()
        try {
            controller.connect(display)
            val endpoint = controller.preparePlayback(
                "Films/Big Movie.mkv", display,
                requestedModel = model,
                passthrough4k = passthrough4k,
                knownSourceSize = known,
                sourceSizeProbe = { url ->
                    probed += url
                    probe()
                },
            )
            val seeked = connect(endpoint).use {
                controller.startServerPlayback()
                controller.expectPlayerReload()
                controller.seek(targetPts = 90_000)
            }
            return Opened(control, endpoint, seeked, probed)
        } finally {
            controller.close()
            media.close()
        }
    }

    @Test(timeout = 20_000)
    fun `passthrough4k opens a 4K library file with passthrough, through a seek too`() = runBlocking {
        val uhd = VideoSize(3840, 2160)
        val opened = openLibraryFile { uhd }
        assertEquals(listOf("http://127.0.0.1:8590/media/Films/Big%20Movie.mkv"), opened.probed)
        assertEquals(PASSTHROUGH_MODEL, opened.control.sent("open_session")["model"]?.jsonPrimitive?.content)
        for (endpoint in listOf(opened.endpoint, opened.seeked)) {
            assertEquals(PASSTHROUGH_MODEL, endpoint.model)
            assertEquals("2x_Model", endpoint.chosenModel)
            assertTrue(endpoint.passthroughFor4k)
            assertEquals(PASSTHROUGH_MODEL, endpoint.session.model)
            assertEquals(uhd, endpoint.session.sourceSize)
        }
    }

    @Test(timeout = 30_000)
    fun `a smaller file, a failed or slow probe, and the option off keep the chosen model`() = runBlocking {
        val fhd = VideoSize(1920, 1080)
        val cases = mapOf(
            "1080p" to openLibraryFile { fhd },
            "failed" to openLibraryFile { throw IOException("HTTP 404") },
            "no video" to openLibraryFile { null },
            "slow" to openLibraryFile { awaitCancellation() },
        )
        for ((name, opened) in cases) {
            assertEquals(name, "2x_Model", opened.control.sent("open_session")["model"]?.jsonPrimitive?.content)
            assertEquals(name, "2x_Model", opened.seeked.model)
            assertFalse(name, opened.seeked.passthroughFor4k)
            assertEquals(name, 1, opened.probed.size)
        }
        assertEquals(fhd, cases.getValue("1080p").endpoint.session.sourceSize)
        assertNull(cases.getValue("failed").endpoint.session.sourceSize)

        // Nothing to decide, so nothing is read.
        val off = openLibraryFile(passthrough4k = false) { error("probed with the option off") }
        assertEquals("2x_Model", off.endpoint.model)
        assertNull(off.endpoint.session.sourceSize)
        val chosenPassthrough = openLibraryFile(model = PASSTHROUGH_MODEL) { error("probed for passthrough") }
        assertEquals(PASSTHROUGH_MODEL, chosenPassthrough.endpoint.model)
        assertFalse(chosenPassthrough.endpoint.passthroughFor4k)
        assertTrue(off.probed.isEmpty() && chosenPassthrough.probed.isEmpty())
    }

    @Test(timeout = 20_000)
    fun `a restart that knows the file's size does not read it again`() = runBlocking {
        val uhd = VideoSize(3840, 2160)
        // A probe that would fail now cannot put the 4K file back on the model.
        val restarted = openLibraryFile(known = uhd) { throw IOException("the network is not back yet") }
        assertTrue(restarted.probed.isEmpty())
        assertEquals(PASSTHROUGH_MODEL, restarted.control.sent("open_session")["model"]?.jsonPrimitive?.content)
        assertEquals(uhd, restarted.seeked.session.sourceSize)
        // Known with the option off: kept for the next toggle, and the model stays.
        val off = openLibraryFile(passthrough4k = false, known = uhd) { error("probed with the option off") }
        assertEquals("2x_Model", off.endpoint.model)
        assertEquals(uhd, off.endpoint.session.sourceSize)
    }

    @Test(timeout = 20_000)
    fun `a controller closed while the size is read opens no session`() = runBlocking {
        val media = FakeMedia(ackDelayMillis = 0)
        val control = FakeControl(media.port, models = twoModels)
        val controller = controller(control)
        try {
            controller.connect(display)
            val opened = runCatching {
                controller.preparePlayback(
                    "film.mkv", display,
                    requestedModel = "2x_Model",
                    passthrough4k = true,
                    sourceSizeProbe = {
                        controller.close()
                        VideoSize(3840, 2160)
                    },
                )
            }
            assertTrue(opened.isFailure)
            assertFalse(control.sentAny("open_session"))
        } finally {
            controller.close()
            media.close()
        }
    }

    /** Answers hello, open_session and seek the way the relay server does. */
    private inner class FakeControl(
        private val mediaPort: Int,
        private val seekReplyDelayMillis: Long = 0,
        /** Extra capabilities, session_opened and seek_ready fields, each starting with a comma. */
        private val capabilities: String = "",
        private val opened: String = "",
        private val seekReady: String = "",
        /** capabilities.models, as JSON. */
        private val models: String = """[{"name":"passthrough","scale_factor":1}]""",
    ) : WebSocket, WebSocket.Factory {
        private lateinit var listener: WebSocketListener
        private lateinit var request: Request
        @Volatile private var cancelled = false
        private val messages = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        var lastSeekSession: SessionInfo? = null

        /** The last message of [type] the client sent. */
        fun sent(type: String): JsonObject = messages.last { it["type"]?.jsonPrimitive?.content == type }

        /** Whether the client sent any message of [type]. */
        fun sentAny(type: String): Boolean = messages.any { it["type"]?.jsonPrimitive?.content == type }

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
            messages += message
            when (message["type"]?.jsonPrimitive?.content) {
                "hello" -> receive(
                    """{"type":"capabilities","protocol_version":1,"server_name":"test",""" +
                        """"models":$models,""" +
                        """"quality_tiers":["lossless-hevc"],"library":false$capabilities}""",
                )
                "open_session" -> receive(
                    """{"type":"session_opened","session_id":"s1","media_port":$mediaPort,""" +
                        """"downlink_token":"$token","downlink_codec":"hevc",""" +
                        """"downlink_container":"matroska","downlink_width":1920,""" +
                        """"downlink_height":1080,"epoch":0,"time_base":[1,1000],""" +
                        """"duration_s":600.0$opened}""",
                )
                "seek" -> {
                    val epoch = message.getValue("epoch").jsonPrimitive.int
                    thread(isDaemon = true) {
                        Thread.sleep(seekReplyDelayMillis)
                        receive("""{"type":"seek_ready","epoch":$epoch$seekReady}""")
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
