package org.upscalerelay.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.DisplaySize
import org.upscalerelay.protocol.LibraryNode
import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.SessionInfo
import java.io.Closeable
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

class RelaySessionController(
    val host: String,
    val port: Int = 8590,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMachine = SessionStateMachine()
    val state: StateFlow<SessionState> = stateMachine.state
    private val mutableFailure = MutableStateFlow<FailureDetail?>(null)
    val failure: StateFlow<FailureDetail?> = mutableFailure.asStateFlow()
    private val mutableStats = MutableStateFlow(TransportStats())
    val stats: StateFlow<TransportStats> = mutableStats.asStateFlow()
    private val playerBuffer = AtomicReference(PlayerBufferSnapshot())
    private val closed = AtomicBoolean(false)
    private val currentEpoch = AtomicInteger(0)
    private val suppressBufferReports = AtomicBoolean(false)
    private val desiredPaused = AtomicBoolean(false)

    private var control: ControlChannel? = null
    private var queue: BoundedMediaQueue? = null
    private var receiver: DownlinkReceiver? = null
    private var uplink: UplinkSender? = null
    private var loopback: LoopbackMediaServer? = null
    private var reporter: Job? = null
    private var capabilities: Capabilities? = null
    private var activeSession: SessionInfo? = null
    private var activeModel: String? = null
    private var activeQualityTier: String? = null
    private var activeFitMode: String? = null
    private var activeResizeAlgorithm: String? = null
    private var originalMediaUrl: String? = null

    suspend fun connect(display: DisplaySize): ConnectedLibrary {
        check(!closed.get())
        stateMachine.transition(SessionState.CONNECTING)
        return try {
            val channel = ControlChannel(host, port, ::fail)
            control = channel
            val caps = channel.connect(display)
            require(caps.protocolVersion == MediaFraming.PROTOCOL_VERSION) {
                "server protocol v${caps.protocolVersion} is not supported"
            }
            require("lossless-hevc" in caps.qualityTiers) { "server does not support lossless-hevc" }
            val root = if (caps.hasLibrary) channel.fetchLibrary() else LibraryNode(
                type = LibraryNode.Type.DIRECTORY,
                name = "Server library unavailable",
                path = "",
            )
            capabilities = caps
            stateMachine.transition(SessionState.BROWSING)
            ConnectedLibrary(caps, root)
        } catch (error: Throwable) {
            fail(error)
            throw error
        }
    }

    suspend fun preparePlayback(
        path: String,
        display: DisplaySize,
        requestedModel: String? = null,
        qualityTier: String = "lossless-hevc",
        fitMode: String = "fit",
        resizeAlgorithm: String? = null,
    ): PlaybackEndpoint {
        check(state.value == SessionState.BROWSING)
        stateMachine.transition(SessionState.OPENING)
        return try {
            val caps = requireNotNull(capabilities)
            require(qualityTier in ANDROID_HEVC_TIERS) { "Android does not support tier '$qualityTier'" }
            require(qualityTier in caps.qualityTiers) { "server does not support tier '$qualityTier'" }
            require(fitMode in FIT_MODES) { "unknown fit mode '$fitMode'" }
            require(resizeAlgorithm == null || resizeAlgorithm in caps.resizeAlgorithms) {
                "server does not support resize algorithm '$resizeAlgorithm'"
            }
            val model = requestedModel?.takeIf { requested ->
                caps.models.any { it.name == requested }
            } ?: caps.phaseOneModel
            val session = SessionInfo.fromJson(
                requireNotNull(control).openSession(
                    path, model, display, qualityTier, fitMode, resizeAlgorithm,
                ),
            )
            require(session.uplinkToken == null) { "server_file unexpectedly requires an uplink" }
            require(session.downlinkContainer == "matroska") {
                "Android requires a Matroska downlink, got ${session.downlinkContainer}"
            }
            require(session.downlinkCodec == "hevc") {
                "Android requires HEVC, got ${session.downlinkCodec}"
            }
            require(session.epoch == 0) { "initial session epoch must be zero" }

            val mediaQueue = BoundedMediaQueue(MEDIA_QUEUE_BYTES)
            queue = mediaQueue
            val localServer = LoopbackMediaServer(mediaQueue, ::fail)
            loopback = localServer
            localServer.start()
            val downlink = DownlinkReceiver(
                host = host,
                port = session.mediaPort,
                token = session.downlinkToken,
                expectedEpoch = 0,
                queue = mediaQueue,
                onFailure = ::fail,
            )
            receiver = downlink
            activeSession = session
            activeModel = model
            activeQualityTier = qualityTier
            activeFitMode = fitMode
            activeResizeAlgorithm = session.resizeAlgorithm ?: resizeAlgorithm
            originalMediaUrl = requireNotNull(control).mediaUrl(path)
            currentEpoch.set(session.epoch)
            downlink.start()
            try {
                withTimeout(30_000) { downlink.ready.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw SocketTimeoutException("initial media timed out after 30000 ms")
            }
            startReporter()
            stateMachine.transition(SessionState.BUFFERING)
            PlaybackEndpoint(
                localUrl = localServer.url,
                originalMediaUrl = requireNotNull(originalMediaUrl),
                session = session,
                model = model,
                qualityTier = qualityTier,
                fitMode = fitMode,
                resizeAlgorithm = session.resizeAlgorithm ?: resizeAlgorithm,
            )
        } catch (error: Throwable) {
            fail(error)
            throw error
        }
    }

    suspend fun prepareLocalPlayback(
        source: UplinkMediaSource,
        originalMediaUrl: String,
        display: DisplaySize,
        requestedModel: String? = null,
        qualityTier: String = "lossless-hevc",
        fitMode: String = "fit",
        resizeAlgorithm: String? = null,
    ): PlaybackEndpoint {
        check(state.value == SessionState.BROWSING)
        stateMachine.transition(SessionState.OPENING)
        return try {
            val caps = requireNotNull(capabilities)
            require(qualityTier in ANDROID_HEVC_TIERS) { "Android does not support tier '$qualityTier'" }
            require(qualityTier in caps.qualityTiers) { "server does not support tier '$qualityTier'" }
            require(fitMode in FIT_MODES) { "unknown fit mode '$fitMode'" }
            require(resizeAlgorithm == null || resizeAlgorithm in caps.resizeAlgorithms) {
                "server does not support resize algorithm '$resizeAlgorithm'"
            }
            val model = requestedModel?.takeIf { requested ->
                caps.models.any { it.name == requested }
            } ?: caps.phaseOneModel
            val session = SessionInfo.fromJson(
                requireNotNull(control).openUplinkSession(
                    source.videoInfo, model, display, qualityTier, fitMode, resizeAlgorithm,
                ),
            )
            val token = requireNotNull(session.uplinkToken) { "uplink session did not return a token" }
            require(session.downlinkContainer == "matroska") {
                "Android requires a Matroska downlink, got ${session.downlinkContainer}"
            }
            require(session.downlinkCodec == "hevc") {
                "Android requires HEVC, got ${session.downlinkCodec}"
            }
            require(session.epoch == 0) { "initial session epoch must be zero" }

            val mediaQueue = BoundedMediaQueue(MEDIA_QUEUE_BYTES)
            queue = mediaQueue
            val localServer = LoopbackMediaServer(mediaQueue, ::fail)
            loopback = localServer
            localServer.start()
            val downlink = DownlinkReceiver(
                host = host,
                port = session.mediaPort,
                token = session.downlinkToken,
                expectedEpoch = 0,
                queue = mediaQueue,
                onFailure = ::fail,
            )
            receiver = downlink
            downlink.start()
            try {
                withTimeout(30_000) { downlink.ready.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw SocketTimeoutException("initial media timed out after 30000 ms")
            }
            val sender = withContext(Dispatchers.IO) {
                UplinkSender.connect(host, session.mediaPort, token, source, ::fail)
            }
            uplink = sender
            sender.startEpoch(epoch = 0)

            activeSession = session
            activeModel = model
            activeQualityTier = qualityTier
            activeFitMode = fitMode
            activeResizeAlgorithm = session.resizeAlgorithm ?: resizeAlgorithm
            this.originalMediaUrl = originalMediaUrl
            currentEpoch.set(session.epoch)
            startReporter()
            stateMachine.transition(SessionState.BUFFERING)
            PlaybackEndpoint(
                localUrl = localServer.url,
                originalMediaUrl = originalMediaUrl,
                session = session,
                model = model,
                qualityTier = qualityTier,
                fitMode = fitMode,
                resizeAlgorithm = session.resizeAlgorithm ?: resizeAlgorithm,
            )
        } catch (error: Throwable) {
            if (uplink == null) source.closeQuietly()
            fail(error)
            throw error
        }
    }

    fun startServerPlayback() {
        check(state.value == SessionState.BUFFERING)
        requireNotNull(control).play()
    }

    fun setPaused(paused: Boolean) {
        val current = state.value
        if (paused) {
            check(current == SessionState.PLAYING || current == SessionState.BUFFERING)
            requireNotNull(control).pause()
            desiredPaused.set(true)
            stateMachine.transition(SessionState.PAUSED)
        } else {
            check(current == SessionState.PAUSED)
            requireNotNull(control).play()
            desiredPaused.set(false)
            stateMachine.transition(SessionState.PLAYING)
        }
    }

    /**
     * Switches the persistent downlink to a fresh queue/localhost stream and
     * asks the server to begin the new epoch at an absolute source PTS.
     */
    suspend fun seek(targetPts: Long): PlaybackEndpoint {
        check(
            state.value in setOf(
                SessionState.PLAYING, SessionState.PAUSED, SessionState.BUFFERING, SessionState.SEEKING,
            ),
        )
        stateMachine.transition(SessionState.SEEKING)
        suppressBufferReports.set(true)
        playerBuffer.set(PlayerBufferSnapshot())
        return try {
            val epoch = currentEpoch.incrementAndGet()
            val nextQueue = BoundedMediaQueue(MEDIA_QUEUE_BYTES)
            val nextLoopback = LoopbackMediaServer(nextQueue, ::fail).also { it.start() }
            val previousLoopback = loopback

            requireNotNull(receiver).switchEpoch(epoch, nextQueue)
            queue = nextQueue
            loopback = nextLoopback
            previousLoopback?.close()

            withContext(Dispatchers.IO) { uplink?.stopCurrent() }
            requireNotNull(control).seek(targetPts.coerceAtLeast(0), epoch)
            check(epoch == currentEpoch.get()) { "seek epoch $epoch was superseded" }
            uplink?.startEpoch(epoch, targetPts.coerceAtLeast(0), discontinuity = true)
            val session = requireNotNull(activeSession).copy(epoch = epoch)
            activeSession = session
            stateMachine.transition(
                if (desiredPaused.get()) SessionState.PAUSED else SessionState.BUFFERING,
            )
            PlaybackEndpoint(
                localUrl = nextLoopback.url,
                originalMediaUrl = requireNotNull(originalMediaUrl),
                session = session,
                model = requireNotNull(activeModel),
                qualityTier = requireNotNull(activeQualityTier),
                fitMode = requireNotNull(activeFitMode),
                resizeAlgorithm = activeResizeAlgorithm,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error)
            throw error
        }
    }

    fun markRendering() {
        if (state.value == SessionState.BUFFERING) stateMachine.transition(SessionState.PLAYING)
        suppressBufferReports.set(false)
    }

    fun updatePlayerBuffer(snapshot: PlayerBufferSnapshot) {
        playerBuffer.set(snapshot)
    }

    fun expectPlayerReload() {
        loopback?.expectClientDisconnect()
    }

    private fun startReporter() {
        reporter?.cancel()
        reporter = scope.launch {
            while (isActive) {
                val player = playerBuffer.get()
                val queueSnapshot = queue?.snapshot() ?: QueueSnapshot(0, 0, 1, true)
                val receiverSnapshot = receiver?.snapshot() ?: ReceiverSnapshot(0, 0, 0.0)
                val bitrate = player.bitrateBitsPerSecond.takeIf { it > 0 }
                    ?: (receiverSnapshot.averageMegabitsPerSecond * 1_000_000).roundToLong()
                val queuedMillis = if (bitrate > 0) queueSnapshot.bytes * 8_000 / bitrate else 0
                val bufferedMillis = (player.cacheDurationMillis + queuedMillis).coerceAtLeast(0)
                if (!suppressBufferReports.get()) control?.bufferReport(bufferedMillis)
                mutableStats.value = TransportStats(
                    receivedBytes = receiverSnapshot.totalBytes,
                    receivedPackets = receiverSnapshot.totalPackets,
                    averageMegabitsPerSecond = receiverSnapshot.averageMegabitsPerSecond,
                    queuedBytes = queueSnapshot.bytes,
                    queuedPackets = queueSnapshot.packets,
                    loopbackBytes = loopback?.totalBytesSent() ?: 0,
                    playerCacheMillis = player.cacheDurationMillis,
                    reportedBufferMillis = bufferedMillis,
                )
                delay(500)
            }
        }
    }

    suspend fun teardown() {
        if (closed.get()) return
        val current = state.value
        if (current != SessionState.CLOSING && current != SessionState.DISCONNECTED) {
            stateMachine.transition(SessionState.CLOSING)
        }
        reporter?.cancel()
        reporter = null
        uplink?.close()
        uplink = null
        receiver?.close()
        receiver = null
        loopback?.close()
        loopback = null
        queue?.close()
        queue = null
        runCatching { control?.teardown() }
        control = null
        capabilities = null
        activeSession = null
        activeModel = null
        activeQualityTier = null
        activeFitMode = null
        activeResizeAlgorithm = null
        originalMediaUrl = null
        currentEpoch.set(0)
        suppressBufferReports.set(false)
        desiredPaused.set(false)
        playerBuffer.set(PlayerBufferSnapshot())
        if (state.value == SessionState.CLOSING) stateMachine.transition(SessionState.DISCONNECTED)
    }

    private fun fail(error: Throwable) {
        if (closed.get() || state.value == SessionState.CLOSING || state.value == SessionState.DISCONNECTED) return
        mutableFailure.value = FailureDetail(
            summary = error.message ?: error::class.simpleName.orEmpty(),
            exceptionType = error::class.qualifiedName.orEmpty(),
            kind = classifyFailure(error),
        )
        stateMachine.fail()
        reporter?.cancel()
        uplink?.close()
        receiver?.close()
        loopback?.close()
        queue?.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reporter?.cancel()
        uplink?.close()
        receiver?.close()
        loopback?.close()
        queue?.close()
        control?.close()
        scope.cancel()
    }

    companion object {
        const val MEDIA_QUEUE_BYTES = 256L * 1024 * 1024
        val ANDROID_HEVC_TIERS = setOf(
            "lossless-hevc", "hevc-qp2", "hevc-qp4", "hevc-qp6",
            "hevc-qp10", "hevc-qp14", "hevc-qp18",
        )
        val FIT_MODES = setOf("fit", "cover")
    }
}

data class ConnectedLibrary(val capabilities: Capabilities, val root: LibraryNode)

data class PlaybackEndpoint(
    val localUrl: String,
    val originalMediaUrl: String,
    val session: SessionInfo,
    val model: String,
    val qualityTier: String,
    val fitMode: String,
    val resizeAlgorithm: String?,
)

data class PlayerBufferSnapshot(
    val cacheDurationMillis: Long = 0,
    val bitrateBitsPerSecond: Long = 0,
)

data class TransportStats(
    val receivedBytes: Long = 0,
    val receivedPackets: Long = 0,
    val averageMegabitsPerSecond: Double = 0.0,
    val queuedBytes: Long = 0,
    val queuedPackets: Int = 0,
    val loopbackBytes: Long = 0,
    val playerCacheMillis: Long = 0,
    val reportedBufferMillis: Long = 0,
)

data class FailureDetail(
    val summary: String,
    val exceptionType: String,
    val kind: FailureKind = FailureKind.UNKNOWN,
)
