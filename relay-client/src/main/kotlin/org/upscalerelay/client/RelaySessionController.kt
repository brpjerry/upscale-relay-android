package org.upscalerelay.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.WebSocket
import org.upscalerelay.protocol.BufferTarget
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.DisplaySize
import org.upscalerelay.protocol.DisplayTarget
import org.upscalerelay.protocol.HighBitDepth
import org.upscalerelay.protocol.LibraryNode
import org.upscalerelay.protocol.LibraryPage
import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.SessionResumed
import org.upscalerelay.protocol.SeekProgress
import org.upscalerelay.protocol.VideoSize
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

class RelaySessionController internal constructor(
    val host: String,
    val port: Int,
    private val attachmentCacheRoot: Path?,
    private val webSocketFactory: WebSocket.Factory?,
    private val playerConnectTimeoutMillis: Long,
    private val sourceSizeProbeTimeoutMillis: Long = SOURCE_SIZE_PROBE_TIMEOUT_MILLIS,
    private val serverHeartbeatWindowMillis: Long = SERVER_HEARTBEAT_WINDOW_MILLIS,
) : Closeable {
    constructor(host: String, port: Int = 8590, attachmentCacheRoot: Path? = null) :
        this(host, port, attachmentCacheRoot, null, PLAYER_CONNECT_TIMEOUT_MILLIS)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMachine = SessionStateMachine()
    val state: StateFlow<SessionState> = stateMachine.state
    private val mutableFailure = MutableStateFlow<FailureDetail?>(null)
    val failure: StateFlow<FailureDetail?> = mutableFailure.asStateFlow()
    private val mutableStats = MutableStateFlow(TransportStats())
    val stats: StateFlow<TransportStats> = mutableStats.asStateFlow()
    private val mutableOpeningProgress = MutableStateFlow<String?>(null)

    /** Server loading text while open_session runs (TensorRT engine build). */
    val openingProgress: StateFlow<String?> = mutableOpeningProgress.asStateFlow()
    private val mutableSeekProgress = MutableStateFlow<SeekProgress?>(null)
    val seekProgress: StateFlow<SeekProgress?> = mutableSeekProgress.asStateFlow()
    private val seekProgressTracker = SeekProgressTracker()
    private val teardownMutex = Mutex()
    private val teardownResult = AtomicReference<Result<Unit>?>(null)
    private val seekMutex = Mutex()

    /** Null after first render; only advancing subtitle coverage refreshes the load deadline. */
    fun seekProgressIdleMillis(): Long? = seekProgressTracker.idleMillis()
    private val playerBuffer = AtomicReference(PlayerBufferSnapshot())
    private val closed = AtomicBoolean(false)
    private val currentEpoch = AtomicInteger(0)
    private val suppressBufferReports = AtomicBoolean(false)
    private val desiredPaused = AtomicBoolean(false)
    private val playbackStateLock = Any()

    @Volatile
    private var control: ControlChannel? = null
    private var queue: BoundedMediaQueue? = null
    private var receiver: DownlinkReceiver? = null
    private var uplink: UplinkSender? = null
    private var loopback: LoopbackMediaServer? = null
    private var reporter: Job? = null
    private var seekWatchdog: Job? = null
    private var capabilities: Capabilities? = null
    private var activeSession: SessionInfo? = null

    @Volatile
    private var openedSessionId: String? = null
    private var activeModel: ModelChoice? = null
    private var activeQualityTier: String? = null
    private var activeFitMode: String? = null
    private var activeResizeAlgorithm: String? = null
    private var originalMediaUrl: String? = null
    private var attachmentCache: AttachmentCache? = null
    private var attachmentView: Path? = null
    private var attachmentCacheStats = AttachmentCacheStats()
    private var requestedAuxTracks: String? = null
    private var requestedAuxAttachments: String? = null

    /** The display this session's epochs are mapped for (§3.6); null when it negotiated none. */
    private var negotiatedDisplayTarget: DisplayTarget? = null

    /** The display named in hello, named again by a resume's hello. */
    @Volatile
    private var helloDisplay: DisplaySize? = null

    // -- session resume (PROTOCOL.md §1.2) --------------------------------------
    private val mutableResuming = MutableStateFlow(false)

    /**
     * The control connection was lost and the session is being resumed on a
     * new one. Playback goes on from what is buffered meanwhile, and nothing
     * is reported: if the resume fails, [failure] says so.
     */
    val resuming: StateFlow<Boolean> = mutableResuming.asStateFlow()

    @Volatile
    private var resumeJob: Job? = null

    /** The newest outage's outcome: what the server said once it resumed, or why it did not. */
    @Volatile
    private var resumeOutcome: CompletableDeferred<SessionResumed>? = null
    private val resumeWake = Channel<Unit>(Channel.CONFLATED)

    /** "playing" or "paused": the newest play/pause, checked against the server's state after a resume. */
    @Volatile
    private var playIntent: String? = null

    /** Another connection resumed this session (close code 4001). */
    @Volatile
    private var takenOver = false

    @Volatile
    private var openedResumeToken: String? = null

    @Volatile
    private var openedServerId: String? = null

    suspend fun connect(display: DisplaySize, librarySort: String? = null): ConnectedLibrary {
        check(!closed.get())
        teardownResult.set(null)
        stateMachine.transition(SessionState.CONNECTING)
        return try {
            helloDisplay = display
            val channel = newChannel()
            control = channel
            val caps = channel.connect(display)
            require(caps.protocolVersion == MediaFraming.PROTOCOL_VERSION) {
                "server protocol v${caps.protocolVersion} is not supported"
            }
            require("lossless-hevc" in caps.qualityTiers) { "server does not support lossless-hevc" }
            // Only pass a sort the server advertises; old servers get defaults.
            val rootSort = librarySort?.takeIf { it in caps.librarySortKeys }
            val page = if (caps.hasLibrary) channel.fetchLibrary(sort = rootSort) else LibraryPage(
                directory = LibraryNode(
                    type = LibraryNode.Type.DIRECTORY,
                    name = "Server library unavailable",
                    path = "",
                ),
                nextCursor = null,
            )
            capabilities = caps
            stateMachine.transition(SessionState.BROWSING)
            ConnectedLibrary(caps, page.directory, page.nextCursor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error)
            throw error
        }
    }

    /** A control connection that reports its loss to this controller, as [control] or a resume attempt. */
    private fun newChannel(): ControlChannel {
        lateinit var channel: ControlChannel
        channel = ControlChannel(host, port, { error -> onControlFailure(channel, error) }, webSocketFactory)
        channel.onOpeningProgress = { text -> mutableOpeningProgress.value = text }
        channel.onSeekProgress = { progress ->
            synchronized(seekProgressTracker) {
                if (seekProgressTracker.accept(progress)) mutableSeekProgress.value = progress
            }
        }
        return channel
    }

    /**
     * The token that lets a later connection take this controller's session
     * over and release it (§1.2): null when the session was not opened for a
     * resume, or another connection took it over (releasing it then would
     * end that owner's playback). A secret: never log or persist it.
     */
    val resumeToken: String? get() = openedResumeToken.takeUnless { takenOver }

    /** The server_id of the server that opened the session, when it gave one: the token goes to that server only. */
    val sessionServerId: String? get() = openedServerId

    /** How long the server holds this session once its connection is lost (§1.2); zero when it is not held. */
    val sessionGraceMillis: Long
        get() = ((activeSessionGraceSeconds ?: 0.0) * 1_000).roundToLong()

    @Volatile
    private var activeSessionGraceSeconds: Double? = null

    /**
     * The id of the session this controller opened, from the moment the
     * server announced it, kept after teardown: a later connection asks about
     * it when this one could not get its release acknowledged.
     */
    val sessionId: String? get() = openedSessionId

    /**
     * Waits until the server no longer holds [sessionId], a session whose
     * teardown it never acknowledged because that session's control
     * connection had already died. The server releases a session by itself
     * when its connection drops (within its heartbeat, about 30 s), and
     * /status reports the result, so this is a positive confirmation, not an
     * assumption.
     *
     * Returns once the session is gone. Throws [RelayServerException]
     * (`server_restart_required`) if a native teardown failed, and
     * [TeardownUnconfirmedException] if the session is still held after
     * [timeoutMillis] or the server cannot say; both mean no replacement
     * session may be opened. A transport failure is thrown as it is: the
     * question is still open and can be asked again on the next connection.
     */
    suspend fun awaitReleased(
        sessionId: String,
        timeoutMillis: Long = RELEASE_CONFIRM_TIMEOUT_MILLIS,
        pollMillis: Long = RELEASE_CONFIRM_POLL_MILLIS,
        resumeToken: String? = null,
        issuerServerId: String? = null,
    ) {
        val channel = requireNotNull(control) { "not connected" }
        // A session held for a resume stays listed for its whole grace
        // period, past this wait: take it over and tear it down first (§1.1
        // step 0). /status, polled either way, has the last word.
        if (resumeToken != null) releaseByResume(sessionId, resumeToken, issuerServerId)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            val status = try {
                channel.fetchStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unavailable: RelayHttpStatusException) {
                throw TeardownUnconfirmedException(unavailable)
            } catch (malformed: RuntimeException) {
                throw TeardownUnconfirmedException(malformed)
            }
            if (status.restartRequired) {
                throw RelayServerException(
                    "server_restart_required",
                    "Server native cleanup failed; restart the server",
                )
            }
            if (sessionId !in status.sessionIds) return
            if (System.nanoTime() >= deadline) {
                throw TeardownUnconfirmedException(
                    IOException("the server still holds session $sessionId after $timeoutMillis ms"),
                )
            }
            delay(pollMillis)
        }
    }

    /**
     * Resumes [sessionId] on a connection of its own and tears it down.
     * Returns when that is done or the server refused the takeover (the
     * session may be gone, closing or held with another token: /status
     * says). Throws an IOException, no verdict, when the server could not
     * be asked or did not confirm, and [RelayServerException] when it needs
     * a restart. The token goes only to the server that issued it.
     */
    private suspend fun releaseByResume(sessionId: String, resumeToken: String, issuerServerId: String?) {
        val caps = capabilities ?: return
        if (!caps.sessionResume || !sameServer(issuerServerId, caps)) return
        // Its own connection: the server ends a connection after its
        // teardown, and this controller's is still needed for the next open.
        val channel = ControlChannel(host, port, {}, webSocketFactory)
        try {
            val releasing = channel.connect(helloDisplay ?: DisplaySize(1920, 1080))
            if (!releasing.sessionResume || !sameServer(issuerServerId, releasing)) return
            try {
                channel.resumeSession(sessionId, resumeToken)
            } catch (refused: RelayServerException) {
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw IOException("could not ask the server to release session $sessionId: ${error.message}", error)
            }
            try {
                channel.teardown()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (restart: RelayServerException) {
                throw restart
            } catch (error: Throwable) {
                // No verdict: if the teardown never arrived, the server holds
                // the session again, and the next connection asks again.
                throw IOException("the server did not confirm releasing session $sessionId", error)
            }
        } finally {
            channel.close()
        }
    }

    suspend fun fetchLibraryPage(
        path: String,
        cursor: String? = null,
        limit: Int = LIBRARY_PAGE_SIZE,
        sort: String? = null,
    ): LibraryPage = requireNotNull(control) { "not connected" }
        .fetchLibrary(path, cursor, limit, sort)

    suspend fun preparePlayback(
        path: String,
        display: DisplaySize,
        requestedModel: String? = null,
        qualityTier: String = "lossless-hevc",
        fitMode: String = "fit",
        resizeAlgorithm: String? = null,
        highBitDepth: HighBitDepth? = null,
        displayTarget: DisplayTarget? = null,
        passthrough4k: Boolean = false,
        sourceSizeProbe: SourceSizeProbe? = null,
        knownSourceSize: VideoSize? = null,
        /** How much video the server keeps buffered here (§5); null keeps its default. */
        bufferTargetMillis: Long? = null,
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
            val chosen = ModelChoice.chosenModel(caps, requestedModel)
            // A library file's size is read only when it can change the model
            // and no earlier session of the file read it already (a restart):
            // the probe costs a request or two to /media before the open, and
            // a resume whose probe failed would put a 4K file on the model.
            val sourceSize = knownSourceSize
                ?: if (sourceSizeProbe != null && ModelChoice.sizeMatters(caps, chosen, passthrough4k)) {
                    probeServerFileSize(path, sourceSizeProbe)
                } else {
                    null
                }
            // A controller closed during the probe must not open a session.
            if (closed.get()) throw IOException("the connection closed while reading the video size")
            val choice = ModelChoice.decide(caps, chosen, passthrough4k, sourceSize)
            val model = choice.sent
            val color = ColorRequest.negotiate(caps, highBitDepth, displayTarget)
            val requestMuxedAuxTracks = caps.muxedAuxTracks
            val requestCachedAttachments = requestMuxedAuxTracks && caps.attachmentCacheVersion >= 1
            requestedAuxTracks = if (requestMuxedAuxTracks) "muxed" else null
            requestedAuxAttachments = if (requestMuxedAuxTracks) {
                if (requestCachedAttachments) "cached" else "embedded"
            } else {
                null
            }
            val session = SessionInfo.fromJson(
                requireNotNull(control).openSession(
                    path, model, display, qualityTier, fitMode, resizeAlgorithm,
                    requestMuxedAuxTracks = requestMuxedAuxTracks,
                    requestCachedAttachments = requestCachedAttachments,
                    highBitDepth = color.highBitDepth,
                    displayTarget = color.displayTarget,
                    bufferTargetMillis = bufferTargetMillis,
                    resume = caps.sessionResume,
                ),
            ).copy(model = model, sourceSize = choice.sourceSize)
            rememberOpened(session, caps)
            require(session.uplinkToken == null) { "server_file unexpectedly requires an uplink" }
            require(session.downlinkContainer == "matroska") {
                "Android requires a Matroska downlink, got ${session.downlinkContainer}"
            }
            require(session.downlinkCodec == "hevc") {
                "Android requires HEVC, got ${session.downlinkCodec}"
            }
            require(session.epoch == 0) { "initial session epoch must be zero" }

            val confirmedMuxed = session.auxTracks == "muxed"
            val fontDirectory = if (confirmedMuxed && session.auxAttachments == "cached") {
                val cacheRoot = requireNotNull(attachmentCacheRoot) {
                    "server confirmed cached attachments but no cache directory is configured"
                }
                val channel = requireNotNull(control)
                val cache = AttachmentCache(
                    cacheRoot,
                    AttachmentFetcher { digest, token, destination, maxBytes ->
                        channel.fetchAttachment(digest, token, destination, maxBytes)
                    },
                )
                attachmentCache = cache
                try {
                    cache.materialize(
                        session.sessionId,
                        session.attachmentManifest,
                        requireNotNull(session.attachmentToken),
                    ).also {
                        attachmentView = it.directory
                        attachmentCacheStats = it.stats
                    }.directory
                } catch (error: Throwable) {
                    // The confirmed epoch omitted these bodies. Never continue
                    // with /media or a partial font view; retire the session.
                    try {
                        withContext(NonCancellable) { channel.teardown() }
                    } catch (cleanupError: Throwable) {
                        if (cleanupError !== error) cleanupError.addSuppressed(error)
                        throw cleanupError
                    }
                    throw error
                }
            } else {
                null
            }

            val mediaQueue = BoundedMediaQueue(MEDIA_QUEUE_BYTES)
            queue = mediaQueue
            val localServer = LoopbackMediaServer(mediaQueue, playerConnectTimeoutMillis, ::fail)
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
            activeModel = choice
            activeQualityTier = qualityTier
            activeFitMode = fitMode
            activeResizeAlgorithm = session.resizeAlgorithm ?: resizeAlgorithm
            negotiatedDisplayTarget = color.displayTarget
            originalMediaUrl = if (session.needsExternalMedia) requireNotNull(control).mediaUrl(path) else null
            currentEpoch.set(session.epoch)
            downlink.start()
            try {
                withTimeout(30_000) { downlink.ready.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw SocketTimeoutException("initial media timed out after 30000 ms")
            }
            startReporter()
            stateMachine.transition(SessionState.BUFFERING)
            localServer.armPlayerConnectDeadline()
            PlaybackEndpoint(
                localUrl = localServer.url,
                originalMediaUrl = originalMediaUrl,
                subtitleFontsDirectory = fontDirectory?.toString(),
                auxTracks = if (confirmedMuxed) "muxed" else "external",
                auxAttachments = if (confirmedMuxed) session.auxAttachments else "embedded",
                requestedAuxTracks = requestedAuxTracks,
                requestedAuxAttachments = requestedAuxAttachments,
                attachmentCacheStats = attachmentCacheStats,
                session = session,
                model = model,
                chosenModel = chosen,
                qualityTier = qualityTier,
                fitMode = fitMode,
                resizeAlgorithm = session.resizeAlgorithm ?: resizeAlgorithm,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error)
            throw error
        } finally {
            mutableOpeningProgress.value = null
        }
    }

    /** What a later connection needs to release this session, from the moment the server announced it. */
    private fun rememberOpened(session: SessionInfo, caps: Capabilities) {
        openedSessionId = session.sessionId
        openedResumeToken = session.resumeToken
        openedServerId = caps.serverId
        activeSessionGraceSeconds = session.resumeGraceSeconds
        takenOver = false
        playIntent = null
    }

    /**
     * A library file's stored size, read through /media by [probe]. Null
     * when the probe fails, finds no video or runs past
     * [SOURCE_SIZE_PROBE_TIMEOUT_MILLIS]: the session then opens with the
     * chosen model, as it would without passthrough4k.
     */
    private suspend fun probeServerFileSize(path: String, probe: SourceSizeProbe): VideoSize? {
        val url = requireNotNull(control) { "not connected" }.mediaUrl(path)
        return try {
            withTimeoutOrNull(sourceSizeProbeTimeoutMillis) { probe.videoSize(url) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
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
        highBitDepth: HighBitDepth? = null,
        displayTarget: DisplayTarget? = null,
        passthrough4k: Boolean = false,
        /** How much video the server keeps buffered here (§5); null keeps its default. */
        bufferTargetMillis: Long? = null,
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
            // An uplink's size is the one its own demuxer read.
            val choice = ModelChoice.decide(
                caps,
                ModelChoice.chosenModel(caps, requestedModel),
                passthrough4k,
                VideoSize.of(source.videoInfo.width, source.videoInfo.height),
            )
            val model = choice.sent
            val color = ColorRequest.negotiate(caps, highBitDepth, displayTarget)
            // HDR10+ rides in the frames, so only the first keyframe tells
            // (§3.6). It matters only to a session the server may map.
            val video = if (color.displayTarget != null && source.probeFirstKeyframeHdr10Plus()) {
                source.videoInfo.copy(hdr10Plus = true)
            } else {
                source.videoInfo
            }
            val session = SessionInfo.fromJson(
                requireNotNull(control).openUplinkSession(
                    video, model, display, qualityTier, fitMode, resizeAlgorithm,
                    highBitDepth = color.highBitDepth,
                    displayTarget = color.displayTarget,
                    bufferTargetMillis = bufferTargetMillis,
                    resume = caps.sessionResume,
                ),
            ).copy(
                sourceHasAudio = source.videoInfo.sourceHasAudio,
                sourceHasAuxiliary = source.videoInfo.sourceHasAuxiliary,
                model = model,
                sourceSize = choice.sourceSize,
            )
            rememberOpened(session, caps)
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
            val localServer = LoopbackMediaServer(mediaQueue, playerConnectTimeoutMillis, ::fail)
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
                    .also { uplink = it }
            }
            sender.startEpoch(epoch = 0)

            activeSession = session
            activeModel = choice
            activeQualityTier = qualityTier
            activeFitMode = fitMode
            activeResizeAlgorithm = session.resizeAlgorithm ?: resizeAlgorithm
            negotiatedDisplayTarget = color.displayTarget
            this.originalMediaUrl = originalMediaUrl.takeIf { session.needsExternalMedia }
            currentEpoch.set(session.epoch)
            startReporter()
            stateMachine.transition(SessionState.BUFFERING)
            localServer.armPlayerConnectDeadline()
            PlaybackEndpoint(
                localUrl = localServer.url,
                originalMediaUrl = this.originalMediaUrl,
                subtitleFontsDirectory = null,
                auxTracks = "external",
                auxAttachments = "embedded",
                requestedAuxTracks = null,
                requestedAuxAttachments = null,
                session = session,
                model = model,
                chosenModel = choice.chosen,
                qualityTier = qualityTier,
                fitMode = fitMode,
                resizeAlgorithm = session.resizeAlgorithm ?: resizeAlgorithm,
            )
        } catch (cancelled: CancellationException) {
            if (uplink == null) source.closeQuietly()
            throw cancelled
        } catch (error: Throwable) {
            if (uplink == null) source.closeQuietly()
            fail(error)
            throw error
        } finally {
            mutableOpeningProgress.value = null
        }
    }

    fun startServerPlayback() {
        check(state.value == SessionState.BUFFERING)
        seekProgressTracker.begin(currentEpoch.get())
        startSeekWatchdog(currentEpoch.get())
        synchronized(playbackStateLock) { sendPlayIntent(paused = false) }
    }

    fun setPaused(paused: Boolean) = synchronized(playbackStateLock) {
        val current = state.value
        if (current == SessionState.SEEKING) {
            sendPlayIntent(paused)
            desiredPaused.set(paused)
            return@synchronized
        }
        if (paused) {
            check(current == SessionState.PLAYING || current == SessionState.BUFFERING)
            sendPlayIntent(paused = true)
            desiredPaused.set(true)
            stateMachine.transition(SessionState.PAUSED)
        } else {
            check(current == SessionState.PAUSED)
            sendPlayIntent(paused = false)
            desiredPaused.set(false)
            stateMachine.transition(SessionState.PLAYING)
        }
    }

    /**
     * Sends play or pause, or, while the connection is being resumed, leaves
     * it to the resume: it sends the newest intent once the server has said
     * what state it holds the session in.
     */
    private fun sendPlayIntent(paused: Boolean) {
        playIntent = if (paused) "paused" else "playing"
        if (mutableResuming.value) return
        val channel = requireNotNull(control)
        try {
            if (paused) channel.pause() else channel.play()
        } catch (error: Throwable) {
            // A connection that has just died and will be resumed.
            if (!(mutableResuming.value || channel !== control || resumable())) throw error
        }
    }

    /**
     * Switches the persistent downlink to a fresh queue/localhost stream and
     * asks the server to begin the new epoch at an absolute source PTS.
     *
     * [displayTarget] is the display the new epoch is shown on (§3.6). On a
     * session that negotiated one, it replaces it from this epoch on, and the
     * server remaps when its transfer differs; a session that negotiated none
     * ignores it, as open_session would have. Null keeps the current one.
     */
    suspend fun seek(targetPts: Long, displayTarget: DisplayTarget? = null): PlaybackEndpoint = seekMutex.withLock {
        synchronized(playbackStateLock) {
            check(
                state.value in setOf(
                    SessionState.PLAYING, SessionState.PAUSED, SessionState.BUFFERING, SessionState.SEEKING,
                ),
            )
            stateMachine.transition(SessionState.SEEKING)
        }
        suppressBufferReports.set(true)
        playerBuffer.set(PlayerBufferSnapshot())
        try {
            val epoch = currentEpoch.incrementAndGet()
            synchronized(seekProgressTracker) {
                seekProgressTracker.begin(epoch)
                mutableSeekProgress.value = null
            }
            startSeekWatchdog(epoch)
            val nextQueue = BoundedMediaQueue(MEDIA_QUEUE_BYTES)
            val nextLoopback = LoopbackMediaServer(nextQueue, playerConnectTimeoutMillis, ::fail).also { it.start() }
            val previousLoopback = loopback

            try {
                requireNotNull(receiver).switchEpoch(epoch, nextQueue)
            } catch (error: Throwable) {
                nextLoopback.close()
                nextQueue.close()
                throw error
            }
            queue = nextQueue
            loopback = nextLoopback
            previousLoopback?.close()

            withContext(Dispatchers.IO) { uplink?.stopCurrent() }
            // The endpoint goes out only after seek_ready: on a mapped session
            // that reply is what says the epoch's colour (§3.6), so mpv never
            // loads an epoch the session cannot describe yet.
            if (displayTarget != null && negotiatedDisplayTarget != null) negotiatedDisplayTarget = displayTarget
            val ready = seekReady(targetPts.coerceAtLeast(0), epoch, negotiatedDisplayTarget)
            check(epoch == currentEpoch.get()) { "seek epoch $epoch was superseded" }
            uplink?.startEpoch(epoch, targetPts.coerceAtLeast(0), discontinuity = true)
            val session = requireNotNull(activeSession).copy(epoch = epoch).describedBy(ready)
            activeSession = session
            synchronized(playbackStateLock) {
                stateMachine.transition(
                    if (desiredPaused.get()) SessionState.PAUSED else SessionState.BUFFERING,
                )
            }
            nextLoopback.armPlayerConnectDeadline()
            PlaybackEndpoint(
                localUrl = nextLoopback.url,
                originalMediaUrl = originalMediaUrl,
                subtitleFontsDirectory = attachmentView?.toString(),
                auxTracks = session.auxTracks,
                auxAttachments = if (session.auxTracks == "muxed") session.auxAttachments else "embedded",
                requestedAuxTracks = requestedAuxTracks,
                requestedAuxAttachments = requestedAuxAttachments,
                attachmentCacheStats = attachmentCacheStats,
                session = session,
                model = requireNotNull(activeModel).sent,
                chosenModel = requireNotNull(activeModel).chosen,
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

    /**
     * Sends the seek and waits for its seek_ready, across a lost control
     * connection that is resumed (§1.2): the resumed session's epoch says
     * whether the seek arrived before the loss. If it did, the seek_ready
     * that never came is the resume's answer; if not, it goes again on the
     * new connection.
     */
    private suspend fun seekReady(targetPts: Long, epoch: Int, displayTarget: DisplayTarget?): JsonObject {
        var resumed: SessionResumed? = if (mutableResuming.value) resumeOutcome?.await() else null
        while (true) {
            resumed?.let { if (it.epoch >= epoch) return it.seekReadyFor(epoch) }
            val channel = requireNotNull(control)
            try {
                return channel.seek(targetPts, epoch, displayTarget)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val outcome = resumeOutcome
                if (outcome == null || (channel === control && !mutableResuming.value)) throw error
                // The connection died under the seek and is being resumed
                // (or already was): what the server says decides.
                resumed = outcome.await()
            }
        }
    }

    fun markRendering() {
        seekWatchdog?.cancel()
        seekWatchdog = null
        clearSeekProgress()
        synchronized(playbackStateLock) {
            if (state.value == SessionState.BUFFERING) stateMachine.transition(SessionState.PLAYING)
        }
        suppressBufferReports.set(false)
    }

    fun updatePlayerBuffer(snapshot: PlayerBufferSnapshot) {
        playerBuffer.set(snapshot)
    }

    fun expectPlayerReload() {
        loopback?.expectClientDisconnect()
    }

    private fun startSeekWatchdog(epoch: Int) {
        seekWatchdog?.cancel()
        seekWatchdog = scope.launch {
            while (isActive && epoch == currentEpoch.get()) {
                delay(500)
                if (epoch != currentEpoch.get()) break
                // Nothing can arrive while the connection is being resumed:
                // the resume bounds that wait, and the seek's starts afresh.
                if (mutableResuming.value) seekProgressTracker.refresh()
                val idleMillis = seekProgressTracker.idleMillis() ?: break
                if (idleMillis >= SEEK_PROGRESS_TIMEOUT_MILLIS) {
                    fail(MediaStalledException(
                        "Stream produced no playable media or advancing subtitle index for 60 seconds",
                    ))
                    break
                }
            }
        }
    }

    private fun clearSeekProgress() = synchronized(seekProgressTracker) {
        seekProgressTracker.clear()
        mutableSeekProgress.value = null
    }

    private fun startReporter() {
        reporter?.cancel()
        reporter = scope.launch {
            var lastReportedTarget: Long? = null
            try {
                while (isActive) {
                    val player = playerBuffer.get()
                    val queueSnapshot = queue?.snapshot() ?: QueueSnapshot(0, 0, 1, true)
                    val receiverSnapshot = receiver?.snapshot() ?: ReceiverSnapshot(0, 0, 0.0)
                    val bitrate = player.bitrateBitsPerSecond.takeIf { it > 0 }
                        ?: (receiverSnapshot.averageMegabitsPerSecond * 1_000_000).roundToLong()
                    val queuedMillis = if (bitrate > 0) queueSnapshot.bytes * 8_000 / bitrate else 0
                    val bufferedMillis = (player.cacheDurationMillis + queuedMillis).coerceAtLeast(0)
                    val targetMillis = BufferTarget.normalize(player.bufferTargetMillis)
                    // Nothing is reported while the connection is being
                    // resumed; reports go on from the resumed one.
                    if (!suppressBufferReports.get() && !mutableResuming.value) {
                        val channel = control
                        try {
                            if (channel != null) {
                                channel.bufferReport(bufferedMillis, targetMillis)
                                lastReportedTarget = targetMillis
                            }
                        } catch (error: Throwable) {
                            if (!(mutableResuming.value || channel !== control || resumable())) throw error
                        }
                    }
                    mutableStats.value = TransportStats(
                        receivedBytes = receiverSnapshot.totalBytes,
                        receivedPackets = receiverSnapshot.totalPackets,
                        averageMegabitsPerSecond = receiverSnapshot.averageMegabitsPerSecond,
                        queuedBytes = queueSnapshot.bytes,
                        queuedPackets = queueSnapshot.packets,
                        loopbackBytes = loopback?.totalBytesSent() ?: 0,
                        playerCacheMillis = player.cacheDurationMillis,
                        reportedBufferMillis = bufferedMillis,
                        bufferTargetMillis = lastReportedTarget,
                    )
                    delay(500)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    suspend fun teardown() = teardownMutex.withLock {
        teardownResult.get()?.let {
            it.getOrThrow()
            return@withLock
        }
        // With the transition, so no resume can start unseen after it.
        val endingResume = synchronized(playbackStateLock) {
            val current = state.value
            if (current != SessionState.CLOSING && current != SessionState.DISCONNECTED) {
                stateMachine.transition(SessionState.CLOSING)
            }
            resumeJob
        }
        var teardownError: Throwable? = null
        fun remember(error: Throwable) {
            val existing = teardownError
            if (existing == null) teardownError = error
            else if (existing !== error) existing.addSuppressed(error)
        }
        reporter?.cancel()
        reporter = null
        seekWatchdog?.cancel()
        seekWatchdog = null
        // A resume in progress is not waited for: control is then the dead
        // connection, its teardown goes unacknowledged, and the next
        // connection takes the session over and releases it (resumeToken).
        endingResume?.cancelAndJoin()
        resumeJob = null
        mutableResuming.value = false
        // Every owner must be retired even if an earlier owner's close fails.
        listOf(uplink, receiver, loopback, queue).forEach { owner ->
            try {
                owner?.close()
            } catch (error: Throwable) {
                remember(error)
            }
        }
        uplink = null
        receiver = null
        loopback = null
        queue = null
        try {
            control?.teardown()
        } catch (error: Throwable) {
            // The remote barrier determines whether opening a replacement is safe.
            teardownError?.let { if (it !== error) error.addSuppressed(it) }
            teardownError = error
        } finally {
            control?.close()
            try {
                withContext(NonCancellable) { attachmentCache?.removeView(attachmentView) }
            } catch (error: Throwable) {
                remember(error)
            }
            attachmentView = null
            attachmentCache = null
        }
        control = null
        capabilities = null
        activeSession = null
        activeModel = null
        activeQualityTier = null
        activeFitMode = null
        activeResizeAlgorithm = null
        originalMediaUrl = null
        attachmentCacheStats = AttachmentCacheStats()
        requestedAuxTracks = null
        requestedAuxAttachments = null
        negotiatedDisplayTarget = null
        playIntent = null
        currentEpoch.set(0)
        suppressBufferReports.set(false)
        desiredPaused.set(false)
        clearSeekProgress()
        playerBuffer.set(PlayerBufferSnapshot())
        if (state.value == SessionState.CLOSING) stateMachine.transition(SessionState.DISCONNECTED)
        teardownError?.let {
            teardownResult.set(Result.failure(it))
            throw it
        }
        teardownResult.set(Result.success(Unit))
    }

    // -- session resume (PROTOCOL.md §1.2) ------------------------------------------

    /**
     * Whether a lost control connection would be resumed: the session was
     * opened for a resume, has finished opening and is not being torn down,
     * and no other connection has taken it over.
     */
    private fun resumable(): Boolean =
        openedResumeToken != null && activeSession?.resumeToken != null && !takenOver && !closed.get() &&
            state.value in RESUMABLE_STATES

    /**
     * The control connection [channel] reported [error]. The current one is
     * resumed when the session allows it, and anything else fails the
     * session as before. A resume attempt's connection is the attempt's own
     * business, and the retired one's is nobody's.
     */
    private fun onControlFailure(channel: ControlChannel, error: Throwable) {
        // Decided under the lock a resume installs its connection with: a
        // loss lands either before the install (the attempt sees it) or
        // after it (this sees the installed connection as its own).
        synchronized(playbackStateLock) {
            if (channel !== control) return
            if (error is SessionTakenOverException) takenOver = true
            val session = activeSession
            if (session != null && error !is RelayServerException && error !is SessionTakenOverException &&
                resumable() && !mutableResuming.value
            ) {
                val outcome = CompletableDeferred<SessionResumed>()
                resumeOutcome = outcome
                mutableResuming.value = true
                resumeJob = scope.launch { resume(session, outcome) }.also { job ->
                    // A resume cancelled by teardown must not leave a seek waiting on it.
                    job.invokeOnCompletion { cause -> if (cause != null) outcome.completeExceptionally(cause) }
                }
                return
            }
        }
        fail(error)
    }

    /**
     * Tries the next resume attempt now instead of after its backoff: a
     * network just appeared, so the one waiting has a chance it lacked.
     */
    fun networkAvailable() {
        if (mutableResuming.value) resumeWake.trySend(Unit)
    }

    /**
     * Reconnects and takes the session over until the server's hold has
     * certainly ended, or it refuses (§1.2 step 5). The hold starts when the
     * server notices the loss, up to a heartbeat window after this end did,
     * and lasts the session's grace period. Each attempt is bounded: during
     * a dropout a connect hangs rather than fails. Nothing is reported while
     * it runs, and only its final failure fails the session.
     */
    private suspend fun resume(session: SessionInfo, outcome: CompletableDeferred<SessionResumed>) {
        val graceMillis = ((session.resumeGraceSeconds ?: 0.0) * 1_000).roundToLong()
        val holdMillis = graceMillis + serverHeartbeatWindowMillis
        var deadline = System.nanoTime() + holdMillis * 1_000_000
        var renewed = false
        val delays = RESUME_RETRY_DELAYS_MILLIS.iterator()
        var lastError: Throwable? = null
        var refusal: Throwable? = null
        while (true) {
            val attempt = newChannel()
            var asked = false
            var installed = false
            try {
                val resumed = withTimeoutOrNull(RESUME_ATTEMPT_TIMEOUT_MILLIS) {
                    val caps = attempt.connect(helloDisplay ?: DisplaySize(1920, 1080))
                    // Another server now (a rollback, another network): the
                    // token is a credential, and this one has no use for it.
                    if (!caps.sessionResume) {
                        throw RelayServerException("resume_unsupported", "the server does not resume sessions")
                    }
                    if (!sameServer(openedServerId, caps)) {
                        throw RelayServerException("resume_other_server", "another server answers at this address")
                    }
                    val media = mediaExpected()
                    asked = true
                    val reply = SessionResumed.fromJson(
                        attempt.resumeSession(session.sessionId, requireNotNull(session.resumeToken)),
                    )
                    reply to media
                } ?: throw SocketTimeoutException("resume attempt timed out after $RESUME_ATTEMPT_TIMEOUT_MILLIS ms")
                val (reply, media) = resumed
                // The last checks, the catch-up and the install under the
                // lock play/pause and a later loss take: no intent can go to
                // the dead connection after the catch-up read it, and no loss
                // of this one can go unseen.
                val retired = synchronized(playbackStateLock) {
                    if (!resumable() || resumeOutcome !== outcome) throw CancellationException("session retired")
                    if (!attempt.isOpen) throw IOException("the resumed connection dropped again")
                    catchUp(attempt, reply, playIntent)
                    control.also {
                        control = attempt
                        installed = true
                        mutableResuming.value = false
                    }
                }
                retired?.close()
                if (!mediaIntact(reply, media)) {
                    // A media connection lost in the dropout is not
                    // re-attached: the epoch's stream cannot go on over a new
                    // one. Ended as a dropped downlink always was.
                    val lost = IOException("A media connection to the server was lost during the dropout")
                    outcome.completeExceptionally(lost)
                    fail(lost)
                    return
                }
                outcome.complete(reply)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: RelayServerException) {
                refusal = refused
            } catch (taken: SessionTakenOverException) {
                takenOver = true
                refusal = taken
            } catch (error: Throwable) {
                lastError = error
            } finally {
                // Closed abnormally, never with 1000: the server may already
                // have handed the session to this connection, and a normal
                // close would end it there. An abnormal one holds it again.
                if (!installed) {
                    attempt.close()
                    // Another connection took the session over (4001): never taken back.
                    if (attempt.takenOver) takenOver = true
                }
            }
            if (refusal != null || takenOver) break
            if (asked && !renewed) {
                // The server may have taken the session over before this
                // attempt failed, and its loss starts a new hold there. Once
                // per outage, so unanswered takeovers cannot keep this going.
                renewed = true
                deadline = maxOf(deadline, System.nanoTime() + holdMillis * 1_000_000)
            }
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMillis <= 0) break
            // The last wait is cut short, and one more attempt follows it.
            val wait = minOf(if (delays.hasNext()) delays.next() else RESUME_RETRY_MAX_MILLIS, remainingMillis)
            withTimeoutOrNull(wait) { resumeWake.receive() }
        }
        val error = (refusal as? SessionTakenOverException)
            ?: IOException(
                "The connection to the server was lost and the session could not be resumed" +
                    ((refusal ?: lastError)?.message?.let { " ($it)" } ?: ""),
                refusal ?: lastError,
            )
        synchronized(playbackStateLock) { mutableResuming.value = false }
        outcome.completeExceptionally(error)
        fail(error)
    }

    /**
     * Catches up with what the server did while the connection was gone: a
     * play or pause written just before the loss may never have arrived. A
     * seek is caught up by the seek waiting for it ([seekReady]).
     */
    private fun catchUp(channel: ControlChannel, reply: SessionResumed, intent: String?) {
        val state = reply.state
        if (intent != null && state in SERVER_PLAYBACK_STATES && state != intent) {
            if (intent == "paused") channel.pause() else channel.play()
        }
    }

    /** (downlink, uplink): the media connections this client had set up. */
    private fun mediaExpected(): Pair<Boolean, Boolean> {
        val ready = receiver?.ready
        val downlink = ready != null && ready.isCompleted && !ready.isCancelled &&
            runCatching { ready.getCompleted() }.isSuccess
        return downlink to (uplink != null)
    }

    private fun mediaIntact(reply: SessionResumed, expected: Pair<Boolean, Boolean>): Boolean {
        val (downlink, uplink) = expected
        return !((downlink && reply.downlinkAttached == false) || (uplink && reply.uplinkAttached == false))
    }

    /** Ends a resume in progress because the session failed for another reason. */
    private fun stopResume(error: Throwable) {
        resumeJob?.cancel()
        resumeOutcome?.completeExceptionally(error)
        mutableResuming.value = false
    }

    private fun fail(error: Throwable) {
        if (closed.get() || state.value in setOf(SessionState.CLOSING, SessionState.DISCONNECTED, SessionState.FAILED)) return
        mutableFailure.value = FailureDetail(
            summary = error.message ?: error::class.simpleName.orEmpty(),
            exceptionType = error::class.qualifiedName.orEmpty(),
            kind = classifyFailure(error),
        )
        stateMachine.fail()
        reporter?.cancel()
        seekWatchdog?.cancel()
        stopResume(error)
        clearSeekProgress()
        listOf(uplink, receiver, loopback, queue).forEach { owner ->
            runCatching { owner?.close() }.exceptionOrNull()?.let {
                if (it !== error) error.addSuppressed(it)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reporter?.cancel()
        seekWatchdog?.cancel()
        resumeJob?.cancel()
        listOf(uplink, receiver, loopback, queue, control).forEach { owner ->
            runCatching { owner?.close() }
        }
        scope.cancel()
    }

    companion object {
        const val TEARDOWN_TIMEOUT_MILLIS = 30_000L
        const val SEEK_PROGRESS_TIMEOUT_MILLIS = 60_000L

        /** How long mpv has to connect once an endpoint has been handed out. */
        const val PLAYER_CONNECT_TIMEOUT_MILLIS = 30_000L

        /**
         * How long a library file's size may take to read before the session
         * opens with the chosen model. Over Wi-Fi the desktop's probe took
         * 20 ms to 0.4 s; this is the desktop's per-read timeout.
         */
        const val SOURCE_SIZE_PROBE_TIMEOUT_MILLIS = 10_000L

        /**
         * How long a lost session may stay listed before it counts as stuck.
         * The server can take 30 s to notice a dead control connection (a
         * 20 s heartbeat plus a 10 s pong wait) and bounds its native close
         * at 15 s, and the session is listed for all of that: 45 s when
         * everything goes as slowly as it may. Giving up exactly then would
         * turn a release that was about to be reported into a hard stop.
         */
        const val RELEASE_CONFIRM_TIMEOUT_MILLIS = 60_000L
        const val RELEASE_CONFIRM_POLL_MILLIS = 1_000L

        /**
         * The server notices a dead control connection within its heartbeat
         * window (a ping every 20 s, 10 s for the pong), and its hold starts
         * then: this end's resume deadline adds it to the grace period.
         */
        const val SERVER_HEARTBEAT_WINDOW_MILLIS = 30_000L

        /** One resume attempt: connect, hello, resume_session and its catch-up. */
        const val RESUME_ATTEMPT_TIMEOUT_MILLIS = 10_000L
        val RESUME_RETRY_DELAYS_MILLIS = listOf(500L, 1_000L, 2_000L, 4_000L)
        const val RESUME_RETRY_MAX_MILLIS = 5_000L
        private val RESUMABLE_STATES = setOf(
            SessionState.BUFFERING, SessionState.PLAYING, SessionState.PAUSED, SessionState.SEEKING,
        )
        private val SERVER_PLAYBACK_STATES = setOf("open", "playing", "paused")
        const val MEDIA_QUEUE_BYTES = 256L * 1024 * 1024
        val ANDROID_HEVC_TIERS = setOf(
            "lossless-hevc", "hevc-qp2", "hevc-qp4", "hevc-qp6",
            "hevc-qp10", "hevc-qp14", "hevc-qp18",
        )
        val FIT_MODES = setOf("fit", "cover")
        const val LIBRARY_PAGE_SIZE = 100
    }
}

/** Whether [caps] come from the server with [serverId] (null: it gave none, and the address is all there is). */
internal fun sameServer(serverId: String?, caps: Capabilities): Boolean = serverId == null || caps.serverId == serverId

internal val SessionInfo.needsExternalMedia: Boolean
    get() = auxTracks != "muxed" && sourceHasAuxiliary != false

/**
 * What open_session asks for, limited to what the server advertises: deep
 * colour after capabilities.high_bit_depth (§3.5), and a display only together
 * with deep colour, after capabilities.display_mapping (§3.6). A server
 * without them streams 8-bit as before, so asking never costs a session.
 */
internal data class ColorRequest(val highBitDepth: HighBitDepth?, val displayTarget: DisplayTarget?) {
    companion object {
        fun negotiate(caps: Capabilities, highBitDepth: HighBitDepth?, displayTarget: DisplayTarget?): ColorRequest {
            val depth = highBitDepth?.takeIf { caps.highBitDepth }
            return ColorRequest(depth, displayTarget?.takeIf { depth != null && caps.displayMapping })
        }
    }
}

data class ConnectedLibrary(
    val capabilities: Capabilities,
    val root: LibraryNode,
    val nextCursor: String?,
)

data class PlaybackEndpoint(
    val localUrl: String,
    val originalMediaUrl: String?,
    val subtitleFontsDirectory: String?,
    val auxTracks: String,
    val auxAttachments: String,
    val requestedAuxTracks: String?,
    val requestedAuxAttachments: String?,
    val attachmentCacheStats: AttachmentCacheStats = AttachmentCacheStats(),
    val session: SessionInfo,
    /** The model sent in open_session ([ModelChoice.sent]). */
    val model: String,
    val qualityTier: String,
    val fitMode: String,
    val resizeAlgorithm: String?,
    /** The user's model as the server advertises it ([ModelChoice.chosen]); what a later open asks for again. */
    val chosenModel: String = model,
) {
    /** Passthrough replaced [chosenModel] because the source is 4K or larger. */
    val passthroughFor4k: Boolean
        get() = passthroughStoodIn(model, chosenModel)
}

data class PlayerBufferSnapshot(
    val cacheDurationMillis: Long = 0,
    val bitrateBitsPerSecond: Long = 0,
    /**
     * The buffer the server should keep topped off (§5), as the player sizes
     * it to its cache; null leaves the session's target as it is.
     */
    val bufferTargetMillis: Long? = null,
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
    /** The buffer target the last report asked for; null when it asked for none. */
    val bufferTargetMillis: Long? = null,
)

data class FailureDetail(
    val summary: String,
    val exceptionType: String,
    val kind: FailureKind = FailureKind.UNKNOWN,
)
