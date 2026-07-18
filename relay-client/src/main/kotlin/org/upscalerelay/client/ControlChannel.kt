package org.upscalerelay.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.DisplaySize
import org.upscalerelay.protocol.LibraryNode
import org.upscalerelay.protocol.MediaFraming
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Base64

internal class ControlChannel(
    private val host: String,
    private val port: Int,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val pending = ConcurrentHashMap<String, PendingReply>()
    private val opened = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private lateinit var webSocket: WebSocket

    suspend fun connect(display: DisplaySize): Capabilities {
        val request = Request.Builder().url(controlUrl()).build()
        webSocket = client.newWebSocket(request, Listener())
        deadline(15_000, "control connect") { opened.await() }
        val reply = request(
            expectedType = "capabilities",
            timeoutMillis = 30_000,
            message = buildJsonObject {
                put("type", "hello")
                put("protocol_version", MediaFraming.PROTOCOL_VERSION)
                put("client_name", "relay-android-phase5")
                put("display", buildJsonObject {
                    put("w", display.width)
                    put("h", display.height)
                })
            },
        )
        return Capabilities.fromJson(reply)
    }

    suspend fun fetchLibrary(): LibraryNode {
        val request = Request.Builder().url(httpUrl("library")).build()
        return deadline(30_000, "GET /library") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(request).execute().use {
                    if (!it.isSuccessful) throw IOException("GET /library failed with HTTP ${it.code}")
                    val body = it.body.string()
                    val root = json.parseToJsonElement(body).jsonObject
                    LibraryNode.fromJson(root.getValue("tree").jsonObject)
                }
            }
        }
    }

    suspend fun openSession(
        path: String,
        model: String,
        display: DisplaySize,
        qualityTier: String,
        fitMode: String,
        resizeAlgorithm: String?,
    ): JsonObject = request(
        expectedType = "session_opened",
        timeoutMillis = 240_000,
        message = buildJsonObject {
            put("type", "open_session")
            put("source", buildJsonObject {
                put("type", "server_file")
                put("path", path)
            })
            put("file", buildJsonObject { put("name", path) })
            put("model", model)
            put("quality_tier", qualityTier)
            put("display", buildJsonObject {
                put("w", display.width)
                put("h", display.height)
            })
            put("fit_mode", fitMode)
            if (!resizeAlgorithm.isNullOrBlank()) put("resize_algorithm", resizeAlgorithm)
        },
    )

    suspend fun openUplinkSession(
        video: UplinkVideoInfo,
        model: String,
        display: DisplaySize,
        qualityTier: String,
        fitMode: String,
        resizeAlgorithm: String?,
    ): JsonObject = request(
        expectedType = "session_opened",
        timeoutMillis = 240_000,
        message = buildJsonObject {
            put("type", "open_session")
            put("source", "uplink")
            put("file", buildJsonObject {
                put("name", video.name)
                video.durationSeconds?.let { put("duration_s", it) }
            })
            put("video", buildJsonObject {
                put("codec", video.codec)
                video.extradata?.takeIf(ByteArray::isNotEmpty)?.let {
                    put("extradata_b64", Base64.getEncoder().encodeToString(it))
                }
                put("width", video.width)
                put("height", video.height)
                put("time_base", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(video.timeBaseNumerator))
                    add(kotlinx.serialization.json.JsonPrimitive(video.timeBaseDenominator))
                })
                if (video.averageRateNumerator != null && video.averageRateDenominator != null) {
                    put("avg_rate", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(video.averageRateNumerator))
                        add(kotlinx.serialization.json.JsonPrimitive(video.averageRateDenominator))
                    })
                }
            })
            put("model", model)
            put("quality_tier", qualityTier)
            put("display", buildJsonObject {
                put("w", display.width)
                put("h", display.height)
            })
            put("fit_mode", fitMode)
            if (!resizeAlgorithm.isNullOrBlank()) put("resize_algorithm", resizeAlgorithm)
        },
    )

    fun play() = send(buildJsonObject { put("type", "play") })

    fun pause() = send(buildJsonObject { put("type", "pause") })

    suspend fun seek(targetPts: Long, epoch: Int): JsonObject = request(
        expectedType = "seek_ready",
        timeoutMillis = 60_000,
        message = buildJsonObject {
            put("type", "seek")
            put("target_pts", targetPts)
            put("epoch", epoch)
        },
        accept = { message -> message["epoch"]?.jsonPrimitive?.int == epoch },
    )

    fun mediaUrl(path: String): String = HttpUrl.Builder()
        .scheme("http")
        .host(host)
        .port(port)
        .addPathSegment("media")
        .addPathSegments(path.trimStart('/'))
        .build()
        .toString()

    fun bufferReport(bufferedMillis: Long) = send(buildJsonObject {
        put("type", "buffer_report")
        put("buffered_ms", bufferedMillis.coerceAtLeast(0))
    })

    suspend fun teardown() {
        if (closed.get()) return
        send(buildJsonObject { put("type", "teardown") })
        delay(100)
        close()
    }

    private suspend fun request(
        expectedType: String,
        timeoutMillis: Long,
        message: JsonObject,
        accept: (JsonObject) -> Boolean = { true },
    ): JsonObject {
        val waiter = CompletableDeferred<JsonObject>()
        val reply = PendingReply(waiter, accept)
        check(pending.putIfAbsent(expectedType, reply) == null) {
            "request for '$expectedType' is already pending"
        }
        try {
            send(message)
            return deadline(timeoutMillis, "'$expectedType' reply") { waiter.await() }
        } finally {
            pending.remove(expectedType, reply)
        }
    }

    /**
     * withTimeout that surfaces as an IOException. TimeoutCancellationException
     * extends CancellationException, and letting it escape makes callers treat
     * an ordinary network timeout as their own coroutine being cancelled.
     */
    private suspend fun <T> deadline(millis: Long, what: String, block: suspend () -> T): T =
        try {
            withTimeout(millis) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw SocketTimeoutException("$what timed out after $millis ms")
        }

    private fun send(message: JsonObject) {
        check(!closed.get()) { "control channel is closed" }
        check(webSocket.send(message.toString())) { "control WebSocket rejected message" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val error = IOException("control channel closed")
        pending.values.forEach { it.deferred.completeExceptionally(error) }
        pending.clear()
        if (::webSocket.isInitialized) webSocket.close(1000, "client teardown")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun httpUrl(path: String): HttpUrl = HttpUrl.Builder()
        .scheme("http")
        .host(host)
        .port(port)
        .addPathSegment(path)
        .build()

    private fun controlUrl(): HttpUrl = httpUrl("control")

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse {
                    fail(IOException("invalid control JSON", it))
                    return
                }
            val type = message["type"]?.toString()?.trim('"') ?: return
            if (type == "error") {
                val error = RelayServerException(
                    code = message["code"]?.toString()?.trim('"') ?: "server_error",
                    message = message["message"]?.toString()?.trim('"') ?: "unknown server error",
                )
                pending.values.forEach { it.deferred.completeExceptionally(error) }
                pending.clear()
                if (message["fatal"]?.toString() == "true") fail(error)
                return
            }
            val reply = pending[type] ?: return
            if (reply.accept(message) && pending.remove(type, reply)) {
                reply.deferred.complete(message)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get()) fail(IOException("control channel closed: $code $reason"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(t)
        }

        private fun fail(error: Throwable) {
            if (!opened.isCompleted) opened.completeExceptionally(error)
            pending.values.forEach { it.deferred.completeExceptionally(error) }
            pending.clear()
            if (!closed.get()) onFailure(error)
        }
    }
}

private data class PendingReply(
    val deferred: CompletableDeferred<JsonObject>,
    val accept: (JsonObject) -> Boolean,
)

class RelayServerException(val code: String, message: String) : IOException("$code: $message")
