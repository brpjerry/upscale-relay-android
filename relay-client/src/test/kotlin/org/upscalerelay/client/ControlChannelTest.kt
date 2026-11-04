package org.upscalerelay.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import org.upscalerelay.protocol.DisplayTarget
import org.upscalerelay.protocol.HighBitDepth
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

    @Test
    fun `uplink open sends a reduced sample aspect ratio only for anamorphic sources`() = runBlocking {
        suspend fun sentVideo(numerator: Int?, denominator: Int?): JsonObject {
            val socket = FakeSocket()
            val control = connected(socket)
            control.openUplinkSession(
                UplinkVideoInfo(
                    name = "dvd.mkv", codec = "mpeg2video", extradata = null,
                    width = 720, height = 576,
                    timeBaseNumerator = 1, timeBaseDenominator = 1_000_000,
                    averageRateNumerator = 25, averageRateDenominator = 1,
                    durationSeconds = 60.0,
                    sampleAspectNumerator = numerator, sampleAspectDenominator = denominator,
                ),
                "passthrough", DisplaySize(1920, 1080), "lossless-hevc", "fit", null,
            )
            control.close()
            return socket.sent.last { it["type"]?.jsonPrimitive?.content == "open_session" }
                .getValue("video").jsonObject
        }
        fun JsonObject.sar() = this["sample_aspect_ratio"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }
        assertEquals(listOf(16, 15), sentVideo(64, 60).sar())
        assertEquals(listOf(64, 45), sentVideo(64, 45).sar())
        assertNull(sentVideo(1, 1).sar())
        assertNull(sentVideo(null, null).sar())
        assertNull(sentVideo(16, 0).sar())
        assertNull(sentVideo(-16, 15).sar())
        // PROTOCOL.md reads ratios outside 1/10..10 as square pixels.
        assertEquals(listOf(10, 1), sentVideo(10, 1).sar())
        assertNull(sentVideo(11, 1).sar())
        assertNull(sentVideo(1, 11).sar())
    }

    @Test
    fun `opens carry deep colour and the display, and a display never goes alone`() = runBlocking {
        fun serverFile(depth: HighBitDepth?, target: DisplayTarget?) = serverFileOpenMessage(
            "clip.mkv", "passthrough", DisplaySize(1920, 1080), "lossless-hevc", "fit", null,
            requestMuxedAuxTracks = true, requestCachedAttachments = true,
            highBitDepth = depth, displayTarget = target,
        )
        val mapped = serverFile(HighBitDepth.HDR, DisplayTarget.SDR)
        assertEquals("hdr", mapped["high_bit_depth"]?.jsonPrimitive?.content)
        assertEquals(Json.parseToJsonElement("""{"transfer":"sdr"}"""), mapped["display_target"])
        val deepOnly = serverFile(HighBitDepth.ALL, null)
        assertEquals("all", deepOnly["high_bit_depth"]?.jsonPrimitive?.content)
        assertNull(deepOnly["display_target"])
        // The server maps only a deep-colour session, so a display alone is not sent.
        val neither = serverFile(null, DisplayTarget.SDR)
        assertNull(neither["high_bit_depth"])
        assertNull(neither["display_target"])

        val socket = FakeSocket()
        val control = connected(socket)
        control.openUplinkSession(
            UplinkVideoInfo(
                name = "hdr.mkv", codec = "hevc", extradata = null,
                width = 3840, height = 2160,
                timeBaseNumerator = 1, timeBaseDenominator = 1_000_000,
                averageRateNumerator = 24_000, averageRateDenominator = 1001,
                durationSeconds = 60.0,
            ),
            "passthrough", DisplaySize(2960, 1848), "hevc-qp4", "fit", null,
            highBitDepth = HighBitDepth.HDR, displayTarget = DisplayTarget.SDR,
        )
        control.close()
        val uplink = socket.sent.last { it["type"]?.jsonPrimitive?.content == "open_session" }
        assertEquals("hdr", uplink["high_bit_depth"]?.jsonPrimitive?.content)
        assertEquals(Json.parseToJsonElement("""{"transfer":"sdr"}"""), uplink["display_target"])
    }

    @Test
    fun `an uplink describes its source's colour and HDR only as far as it knows them`() = runBlocking {
        suspend fun sentVideo(info: UplinkVideoInfo): JsonObject {
            val socket = FakeSocket()
            val control = connected(socket)
            control.openUplinkSession(info, "passthrough", DisplaySize(2960, 1848), "hevc-qp4", "fit", null)
            control.close()
            return socket.sent.last { it["type"]?.jsonPrimitive?.content == "open_session" }
                .getValue("video").jsonObject
        }
        val plain = UplinkVideoInfo(
            name = "hdr.mkv", codec = "hevc", extradata = null, width = 3840, height = 2160,
            timeBaseNumerator = 1, timeBaseDenominator = 1_000_000,
            averageRateNumerator = 24, averageRateDenominator = 1, durationSeconds = 20.0,
        )
        // A source the demuxer says nothing about sends nothing: the server
        // reads an HEVC uplink's colour and depth from its parameter sets.
        val untold = sentVideo(plain)
        for (field in listOf("color", "bit_depth", "hdr", "dolby_vision", "hdr10plus")) assertNull(field, untold[field])

        val told = sentVideo(plain.copy(
            color = org.upscalerelay.protocol.VideoColor(9, 16, 9, "limited"),
            bitDepth = 10,
            staticHdr = org.upscalerelay.protocol.StaticHdrMetadata(
                contentLightLevel = org.upscalerelay.protocol.ContentLightLevel(1000, 400),
            ),
            dolbyVision = org.upscalerelay.protocol.DolbyVisionConfig(8, 6, 1),
            hdr10Plus = true,
        ))
        assertEquals(Json.parseToJsonElement("""{"primaries":9,"transfer":16,"matrix":9,"range":"limited"}"""), told["color"])
        assertEquals(10, told.getValue("bit_depth").jsonPrimitive.content.toInt())
        assertEquals(Json.parseToJsonElement("""{"content_light_level":{"max_cll":1000,"max_fall":400}}"""), told["hdr"])
        assertEquals(Json.parseToJsonElement("""{"profile":8,"level":6,"bl_signal_compatibility_id":1}"""), told["dolby_vision"])
        assertEquals("true", told.getValue("hdr10plus").jsonPrimitive.content)
        // The server reads depths outside 8..16 as unknown, so none is sent.
        assertNull(sentVideo(plain.copy(bitDepth = 4))["bit_depth"])
    }

    @Test
    fun `a seek names the display for its epoch only when given one`() = runBlocking {
        val socket = FakeSocket()
        val control = connected(socket)
        suspend fun seek(epoch: Int, target: DisplayTarget?): JsonObject {
            val seek = async(start = CoroutineStart.UNDISPATCHED) { control.seek(1_000, epoch, target) }
            socket.receive("""{"type":"seek_ready","epoch":$epoch}""")
            withTimeout(2_000) { seek.await() }
            return socket.sent.last { it["type"]?.jsonPrimitive?.content == "seek" }
        }
        assertEquals(Json.parseToJsonElement("""{"transfer":"sdr"}"""), seek(1, DisplayTarget.SDR)["display_target"])
        assertNull(seek(2, null)["display_target"])
        control.close()
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
