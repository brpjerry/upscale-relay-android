package org.upscalerelay.android

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.upscalerelay.client.FailureDetail
import org.upscalerelay.client.FailureKind
import org.upscalerelay.client.MediaStalledException
import org.upscalerelay.client.PlaybackEndpoint
import org.upscalerelay.client.PlayerBufferSnapshot
import org.upscalerelay.client.ReconnectPolicy
import org.upscalerelay.client.RelaySessionController
import org.upscalerelay.client.SessionState
import org.upscalerelay.client.TransportStats
import org.upscalerelay.demux.AndroidMediaSource
import org.upscalerelay.demux.LocalDocumentHttpServer
import org.upscalerelay.demux.LocalDocumentBrowser
import org.upscalerelay.demux.LocalDocumentEntry
import org.upscalerelay.player.mpv.MpvMetrics
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.player.mpv.MpvPlayerEngine
import org.upscalerelay.player.mpv.MpvTrack
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.DisplaySize
import org.upscalerelay.protocol.LibraryNode
import org.upscalerelay.protocol.SessionInfo
import java.io.File
import java.time.Instant
import kotlin.math.roundToLong

class RelayViewModel(application: Application) : AndroidViewModel(application) {
    val playerEngine = MpvPlayerEngine(application)
    private val mutableUi = MutableStateFlow(RelayUiState())
    val ui: StateFlow<RelayUiState> = mutableUi.asStateFlow()
    private var controller: RelaySessionController? = null
    private var controllerCollectors: Job? = null
    private var metricsJob: Job? = null
    private var seekJob: Job? = null
    private var sessionStartedAt: Instant? = null
    private var playerVersions: Map<String, String> = emptyMap()
    private val preferences = AppPreferencesStore(application)
    private var autoConnectAttempted = false
    private var subtitlePreferenceAppliedSession: String? = null
    private var localDocumentServer: LocalDocumentHttpServer? = null
    private var localDocumentUri: String? = null
    private var localTreeUri: Uri? = null
    private var localDirectoryStack: List<Pair<Uri, String>> = emptyList()

    // Phase 5: discovery, automatic reconnect/resume, and playback watchdogs.
    private val discovery = ServerDiscovery(application)
    private var reconnectJob: Job? = null
    private var restartJob: Job? = null
    private var reconnectExhausted = false
    private var activeOrigin: PlaybackOrigin? = null
    private val networkEpoch = MutableStateFlow(0L)
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var resumeCount = 0
    private var lastReceivedBytes = 0L
    private var lastReceiveChangeAt = 0L
    private var lastPausedForCache = false
    private var rebufferTimestamps: List<Long> = emptyList()
    private var metricsStartedAt = 0L
    private var playbackPositions: Map<String, Double> = emptyMap()
    private var lastProgressSaveAt = 0L
    private var decoderDropsWindow: Pair<Long, Long> = 0L to 0L
    private var warningDismissed = false

    private sealed interface PlaybackOrigin {
        data class ServerFile(val path: String) : PlaybackOrigin
        data class LocalDocument(val uriValue: String) : PlaybackOrigin
    }

    private fun progressKey(origin: PlaybackOrigin): String = when (origin) {
        is PlaybackOrigin.ServerFile -> "server:${origin.path}"
        is PlaybackOrigin.LocalDocument -> "local:${origin.uriValue}"
    }

    // Phase 5.5: system media integration.
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var publishedMetadataKey: String? = null
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // Explicit policy: with background playback off, leaving the app
            // pauses; with it on, the foreground service keeps audio running
            // while the detached Surface parks video in the null vo.
            val state = mutableUi.value
            if (!state.backgroundPlayback && state.playingPath != null && !state.paused &&
                state.reconnecting == null
            ) {
                runCatching { togglePaused() }
            }
        }
    }
    private val bridgeControls = object : PlaybackBridge.Controls {
        override fun togglePlayPause() {
            viewModelScope.launch { runCatching { togglePaused() } }
        }

        override fun playbackSeekBy(seconds: Double) {
            viewModelScope.launch { runCatching { seekRelative(seconds) } }
        }

        override fun stopPlayback() {
            viewModelScope.launch { runCatching { closePlayback() } }
        }
    }

    private var metricsTickCount = 0L

    init {
        playerEngine.logSink = { level, line ->
            // mpv lines already reach logcat inside the engine.
            AppLog.fileOnly(if (level <= 20) 'E' else if (level <= 30) 'W' else 'I', "mpv", line)
        }
        runCatching {
            playerEngine.initialize()
            playerVersions = playerEngine.versionInfo()
        }.onFailure { error ->
            mutableUi.value = mutableUi.value.copy(
                error = "libmpv initialization failed: ${error.message ?: error::class.simpleName}",
            )
        }
        viewModelScope.launch {
            playerEngine.state.collectLatest { state ->
                if (state == MpvPlaybackState.PLAYING) controller?.markRendering()
                if (state == MpvPlaybackState.ENDED) {
                    AppLog.i(TAG, "playback ended (natural EOS)")
                    // A completed playthrough starts from the beginning next time.
                    activeOrigin?.let { origin ->
                        persist { preferences.clearPlaybackPosition(progressKey(origin)) }
                    }
                }
                mutableUi.value = mutableUi.value.copy(playerState = state)
            }
        }
        val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkEpoch.update { it + 1 }
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess { connectivityCallback = networkCallback }
        discovery.start()
        PlaybackBridge.controls = bridgeControls
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        viewModelScope.launch {
            discovery.servers.collectLatest { servers ->
                mutableUi.update { it.copy(discoveredServers = servers) }
            }
        }
        viewModelScope.launch {
            preferences.values.collectLatest { value ->
                val firstLoad = !mutableUi.value.preferencesLoaded
                mutableUi.update { state ->
                    state.copy(
                        host = value.host,
                        port = value.port.toString(),
                        autoConnect = value.autoConnect,
                        autoResume = value.autoResume,
                        selectedModel = value.model,
                        qualityTier = value.qualityTier,
                        fitMode = value.fitMode,
                        resizeAlgorithm = value.resizeAlgorithm,
                        debandEnabled = value.debandEnabled,
                        subtitlesEnabled = value.subtitlesEnabled,
                        preferredSubtitle = value.preferredSubtitle,
                        diagnosticsVisible = value.diagnosticsVisible,
                        gesturesEnabled = value.gesturesEnabled,
                        displayResampleSync = value.displayResampleSync,
                        interpolationEnabled = value.interpolationEnabled,
                        interpolationScaler = value.interpolationScaler,
                        backgroundPlayback = value.backgroundPlayback,
                        destination = if (firstLoad) {
                            TabletDestination.entries.firstOrNull { it.name == value.lastDestination }
                                ?: state.destination
                        } else {
                            state.destination
                        },
                        recentPaths = value.recentPaths,
                        recentLocalUris = value.recentLocalUris,
                        recentLocalRootUris = value.recentLocalRootUris,
                        preferencesLoaded = true,
                    )
                }
                playbackPositions = value.playbackPositions
                syncFileLogging(value.fileLoggingEnabled)
                playerEngine.setDeband(value.debandEnabled)
                playerEngine.setVideoSyncPreferences(
                    displayResample = value.displayResampleSync,
                    interpolation = value.interpolationEnabled,
                    scaler = value.interpolationScaler,
                )
                if (firstLoad && value.autoConnect && !autoConnectAttempted) {
                    autoConnectAttempted = true
                    connect()
                }
            }
        }
    }

    fun setHost(value: String) {
        mutableUi.value = mutableUi.value.copy(host = value)
        persist { preferences.setHost(value) }
    }

    fun setPort(value: String) {
        val filtered = value.filter(Char::isDigit)
        mutableUi.value = mutableUi.value.copy(port = filtered)
        filtered.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port ->
            persist { preferences.setPort(port) }
        }
    }

    fun selectDestination(value: TabletDestination) {
        mutableUi.value = mutableUi.value.copy(destination = value)
        persist { preferences.setLastDestination(value.name) }
    }

    fun setDisplayResampleSync(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(displayResampleSync = value)
        applyVideoSyncPreferences()
        persist { preferences.setDisplayResampleSync(value) }
    }

    fun setInterpolationEnabled(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(interpolationEnabled = value)
        applyVideoSyncPreferences()
        persist { preferences.setInterpolationEnabled(value) }
    }

    fun setInterpolationScaler(value: String) {
        if (value !in MpvPlayerEngine.INTERPOLATION_SCALERS) return
        mutableUi.value = mutableUi.value.copy(interpolationScaler = value)
        applyVideoSyncPreferences()
        persist { preferences.setInterpolationScaler(value) }
    }

    fun setBackgroundPlayback(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(backgroundPlayback = value)
        persist { preferences.setBackgroundPlayback(value) }
    }

    fun setFileLoggingEnabled(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(fileLoggingEnabled = value)
        persist { preferences.setFileLoggingEnabled(value) }
        syncFileLogging(value)
    }

    /** Starts/stops the Documents log to match the preference (idempotent). */
    private fun syncFileLogging(enabled: Boolean) {
        if (enabled == AppLog.active) {
            mutableUi.update { it.copy(fileLoggingEnabled = enabled, logFileName = AppLog.currentFileName) }
            return
        }
        if (!enabled) {
            AppLog.i(TAG, "file logging disabled")
            AppLog.attach(null)
            mutableUi.update { it.copy(fileLoggingEnabled = false, logFileName = null) }
            return
        }
        val logger = FileLogger.start(getApplication())
        AppLog.attach(logger)
        if (logger == null) {
            mutableUi.update {
                it.copy(
                    fileLoggingEnabled = false,
                    logFileName = null,
                    error = "Unable to create the log file in Documents.",
                )
            }
            persist { preferences.setFileLoggingEnabled(false) }
            return
        }
        val app = getApplication<Application>()
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        AppLog.i(TAG, "=== Upscale Relay $version · file logging enabled ===")
        AppLog.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        AppLog.i(TAG, "fingerprint=${Build.FINGERPRINT}")
        AppLog.i(TAG, "mpv=${playerVersions["mpv"]} ffmpeg=${playerVersions["ffmpeg"]}")
        mutableUi.update { it.copy(fileLoggingEnabled = true, logFileName = logger.displayName) }
    }

    private fun applyVideoSyncPreferences() {
        val state = mutableUi.value
        playerEngine.setVideoSyncPreferences(
            displayResample = state.displayResampleSync,
            interpolation = state.interpolationEnabled,
            scaler = state.interpolationScaler,
        )
    }

    fun setAutoConnect(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(autoConnect = value)
        persist { preferences.setAutoConnect(value) }
    }

    fun setAutoResume(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(autoResume = value)
        persist { preferences.setAutoResume(value) }
    }

    /** Connect to a server discovered over mDNS; manual entry stays untouched. */
    fun connectTo(server: DiscoveredServer) {
        if (mutableUi.value.busy) return
        mutableUi.value = mutableUi.value.copy(host = server.host, port = server.port.toString())
        persist {
            preferences.setHost(server.host)
            preferences.setPort(server.port)
        }
        connect()
    }

    fun cancelAutoResume() {
        val job = reconnectJob ?: return
        reconnectJob = null
        reconnectExhausted = true // trailing attempt failures must not re-arm
        viewModelScope.launch {
            job.cancelAndJoin()
            mutableUi.update {
                it.copy(reconnecting = null, error = it.error ?: "Automatic reconnect cancelled.")
            }
        }
    }

    fun dismissPerformanceWarning() {
        warningDismissed = true
        mutableUi.update { it.copy(performanceWarning = null) }
    }

    fun updateDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        mutableUi.value = mutableUi.value.copy(display = DisplaySize(width, height))
    }

    fun setQualityTier(value: String) {
        if (value in RelaySessionController.ANDROID_HEVC_TIERS) {
            val changed = mutableUi.value.qualityTier != value
            mutableUi.value = mutableUi.value.copy(qualityTier = value)
            persist { preferences.setQualityTier(value) }
            if (changed) maybeRestartActiveSession()
        }
    }

    fun setFitMode(value: String) {
        if (value in RelaySessionController.FIT_MODES) {
            val changed = mutableUi.value.fitMode != value
            mutableUi.value = mutableUi.value.copy(fitMode = value)
            persist { preferences.setFitMode(value) }
            if (changed) maybeRestartActiveSession()
        }
    }

    fun setResizeAlgorithm(value: String) {
        val advertised = mutableUi.value.capabilities?.resizeAlgorithms.orEmpty()
        if (value.isNotEmpty() && advertised.isNotEmpty() && value !in advertised) return
        val changed = mutableUi.value.resizeAlgorithm != value
        mutableUi.value = mutableUi.value.copy(resizeAlgorithm = value)
        persist { preferences.setResizeAlgorithm(value) }
        if (changed) maybeRestartActiveSession()
    }

    fun setDebandEnabled(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(debandEnabled = value)
        playerEngine.setDeband(value)
        persist { preferences.setDebandEnabled(value) }
    }

    fun setModel(value: String) {
        val advertised = mutableUi.value.capabilities?.models.orEmpty().map { it.name }
        if (advertised.isNotEmpty() && value !in advertised) return
        val changed = mutableUi.value.selectedModel != value
        mutableUi.value = mutableUi.value.copy(selectedModel = value)
        persist { preferences.setModel(value) }
        if (changed) maybeRestartActiveSession()
    }

    fun setSubtitlesEnabled(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(subtitlesEnabled = value)
        persist { preferences.setSubtitlesEnabled(value) }
        if (mutableUi.value.endpoint != null) {
            val subtitles = mutableUi.value.tracks.filter { it.type == MpvTrack.Type.SUBTITLE }
            val preferred = subtitles.firstOrNull {
                it.preferenceKey == mutableUi.value.preferredSubtitle
            } ?: subtitles.firstOrNull()
            playerEngine.selectSubtitleTrack(if (value) preferred?.id else null)
        }
    }

    fun setDiagnosticsVisible(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(diagnosticsVisible = value)
        persist { preferences.setDiagnosticsVisible(value) }
    }

    fun setGesturesEnabled(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(gesturesEnabled = value)
        persist { preferences.setGesturesEnabled(value) }
    }

    fun clearRecents() {
        persist { preferences.clearRecents() }
    }

    fun dismissError() {
        mutableUi.value = mutableUi.value.copy(error = null)
    }

    fun connect() {
        if (mutableUi.value.busy) return
        viewModelScope.launch {
            val host = mutableUi.value.host.trim()
            val port = mutableUi.value.port.toIntOrNull()
            if (host.isBlank() || port == null || port !in 1..65535) {
                mutableUi.value = mutableUi.value.copy(error = "Enter a valid host and port.")
                return@launch
            }
            connectInternal(host, port)
        }
    }

    private suspend fun connectInternal(host: String, port: Int) {
        // OkHttp's WebSocket close path may touch the socket synchronously.
        // Retrying therefore must not dispose the previous controller on the
        // Android main thread.
        withContext(Dispatchers.IO) { disposeController() }
        mutableUi.value = mutableUi.value.copy(
            busy = true,
            error = null,
            endpoint = null,
            session = null,
            playingPath = null,
            paused = false,
            seeking = false,
            seekPreviewSeconds = null,
            currentDirectory = null,
            capabilities = null,
            libraryRoot = null,
            selectedLibraryNode = null,
        )
        AppLog.i(TAG, "connect $host:$port display=${mutableUi.value.display.width}x${mutableUi.value.display.height}")
        val next = RelaySessionController(host, port)
        controller = next
        collectController(next)
        runCatching { next.connect(mutableUi.value.display) }
            .onSuccess { connected ->
                val selectedModel = mutableUi.value.selectedModel.takeIf { selected ->
                    connected.capabilities.models.any { it.name == selected }
                } ?: connected.capabilities.phaseOneModel
                val androidQualities = connected.capabilities.qualityOptions
                    .filter { it.androidSupported && it.id in RelaySessionController.ANDROID_HEVC_TIERS }
                val selectedQuality = mutableUi.value.qualityTier.takeIf { selected ->
                    androidQualities.any { it.id == selected }
                } ?: androidQualities.firstOrNull()?.id ?: "lossless-hevc"
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    capabilities = connected.capabilities,
                    libraryRoot = connected.root,
                    currentDirectory = connected.root,
                    directoryStack = emptyList(),
                    selectedLibraryNode = null,
                    selectedModel = selectedModel,
                    qualityTier = selectedQuality,
                )
                persist {
                    preferences.setModel(selectedModel)
                    preferences.setQualityTier(selectedQuality)
                }
            }
            .onFailure { error ->
                mutableUi.value = mutableUi.value.copy(busy = false, error = error.message)
            }
    }

    fun openDirectory(directory: LibraryNode) {
        if (directory.type != LibraryNode.Type.DIRECTORY) return
        val current = mutableUi.value.currentDirectory ?: return
        mutableUi.value = mutableUi.value.copy(
            currentDirectory = directory,
            directoryStack = mutableUi.value.directoryStack + current,
            selectedLibraryNode = null,
        )
    }

    fun selectLibraryNode(node: LibraryNode) {
        mutableUi.value = mutableUi.value.copy(selectedLibraryNode = node)
    }

    /** Compact windows show detail full-screen; Back clears the selection. */
    fun clearLibrarySelection() {
        mutableUi.value = mutableUi.value.copy(selectedLibraryNode = null)
    }

    fun upDirectory() {
        val stack = mutableUi.value.directoryStack
        if (stack.isEmpty()) return
        mutableUi.value = mutableUi.value.copy(
            currentDirectory = stack.last(),
            directoryStack = stack.dropLast(1),
            selectedLibraryNode = null,
        )
    }

    fun openRecent(path: String) {
        val file = mutableUi.value.libraryRoot?.findPath(path)
        if (file?.type == LibraryNode.Type.FILE) openFile(file)
        else mutableUi.value = mutableUi.value.copy(
            destination = TabletDestination.SERVER,
            error = "Reconnect to the server to open this recent item.",
        )
    }

    fun openRecentLocal(uri: String) = openLocalDocument(uri)

    fun openLocalTree(uriValue: String) {
        if (mutableUi.value.busy) return
        viewModelScope.launch {
            mutableUi.value = mutableUi.value.copy(busy = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    val tree = uriValue.toUri()
                    val root = LocalDocumentBrowser.rootDocumentUri(tree)
                    val name = LocalDocumentBrowser.displayName(getApplication(), root)
                    Triple(tree, root, name) to LocalDocumentBrowser.children(getApplication(), tree, root)
                }
            }.onSuccess { (rootInfo, entries) ->
                val (tree, root, name) = rootInfo
                localTreeUri = tree
                localDirectoryStack = listOf(root to name)
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    localDirectoryName = name,
                    localEntries = entries,
                    localCanGoUp = false,
                )
                persist { preferences.addRecentLocalRootUri(uriValue) }
            }.onFailure { error ->
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    error = error.message ?: "Unable to open the selected folder.",
                )
            }
        }
    }

    fun openLocalEntry(entry: LocalDocumentEntry) {
        if (!entry.isDirectory) {
            openLocalDocument(entry.uri)
            return
        }
        val tree = localTreeUri ?: return
        viewModelScope.launch {
            mutableUi.value = mutableUi.value.copy(busy = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    LocalDocumentBrowser.children(getApplication(), tree, entry.uri.toUri())
                }
            }.onSuccess { entries ->
                localDirectoryStack = localDirectoryStack + (entry.uri.toUri() to entry.name)
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    localDirectoryName = entry.name,
                    localEntries = entries,
                    localCanGoUp = true,
                )
            }.onFailure { error ->
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    error = error.message ?: "Unable to open the selected folder.",
                )
            }
        }
    }

    fun upLocalDirectory() {
        val tree = localTreeUri ?: return
        if (localDirectoryStack.size <= 1 || mutableUi.value.busy) return
        val nextStack = localDirectoryStack.dropLast(1)
        val (directory, name) = nextStack.last()
        viewModelScope.launch {
            mutableUi.value = mutableUi.value.copy(busy = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    LocalDocumentBrowser.children(getApplication(), tree, directory)
                }
            }.onSuccess { entries ->
                localDirectoryStack = nextStack
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    localDirectoryName = name,
                    localEntries = entries,
                    localCanGoUp = nextStack.size > 1,
                )
            }.onFailure { error ->
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    error = error.message ?: "Unable to open the parent folder.",
                )
            }
        }
    }

    fun openLocalDocument(uriValue: String) {
        if (mutableUi.value.busy) return
        viewModelScope.launch {
            if (controller?.state?.value != SessionState.BROWSING) {
                val host = mutableUi.value.host.trim()
                val port = mutableUi.value.port.toIntOrNull()
                if (host.isBlank() || port == null || port !in 1..65535) {
                    mutableUi.value = mutableUi.value.copy(error = "Enter a valid server host and port.")
                    return@launch
                }
                connectInternal(host, port)
            }
            val currentController = controller
            if (currentController?.state?.value != SessionState.BROWSING ||
                mutableUi.value.capabilities == null
            ) {
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    error = mutableUi.value.error ?: "Unable to connect to the upscale server.",
                )
                return@launch
            }
            mutableUi.value = mutableUi.value.copy(
                busy = true,
                error = null,
                playingPath = uriValue,
                localPlayback = true,
                directLocalFallback = false,
            )
            var bridge: LocalDocumentHttpServer? = null
            runCatching {
                val landscape = withTimeoutOrNull(5_000) {
                    ui.first { it.display.width > it.display.height }
                }
                checkNotNull(landscape) { "Timed out waiting for the landscape player surface." }
                val (source, localBridge) = withContext(Dispatchers.IO) {
                    val uri = uriValue.toUri()
                    AndroidMediaSource.open(getApplication(), uri) to
                        LocalDocumentHttpServer(getApplication(), uri)
                }
                bridge = localBridge
                val endpoint = currentController.prepareLocalPlayback(
                    source = source,
                    originalMediaUrl = localBridge.url,
                    display = landscape.display,
                    requestedModel = landscape.selectedModel,
                    qualityTier = landscape.qualityTier,
                    fitMode = landscape.fitMode,
                    resizeAlgorithm = landscape.resizeAlgorithm.ifEmpty { null },
                )
                applyResumePoint(currentController, endpoint, "local:$uriValue") to
                    source.videoInfo.name
            }.onSuccess { (endpoint, displayName) ->
                AppLog.i(TAG, "opened local file '$displayName' session=${endpoint.session.sessionId} model=${endpoint.model} tier=${endpoint.qualityTier} out=${endpoint.session.downlinkWidth}x${endpoint.session.downlinkHeight} epoch=${endpoint.session.epoch}")
                localDocumentServer?.close()
                localDocumentServer = bridge
                localDocumentUri = uriValue
                activeOrigin = PlaybackOrigin.LocalDocument(uriValue)
                warningDismissed = false
                reconnectExhausted = false
                sessionStartedAt = Instant.now()
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    endpoint = endpoint.localUrl,
                    session = endpoint.session,
                    playingPath = displayName,
                    selectedModel = endpoint.model,
                    sessionDescription =
                        "${endpoint.model} · ${endpoint.qualityTier} · " +
                            "${endpoint.session.downlinkWidth}×${endpoint.session.downlinkHeight}",
                )
                subtitlePreferenceAppliedSession = null
                persist {
                    preferences.setModel(endpoint.model)
                    preferences.addRecentLocalUri(uriValue)
                }
                playerEngine.setPanscan(0.0)
                playerEngine.load(endpoint.localUrl, endpoint.originalMediaUrl)
                currentController.startServerPlayback()
                startMetrics(currentController)
            }.onFailure { error ->
                val fallbackBridge = bridge
                localDocumentServer?.close()
                localDocumentServer = fallbackBridge
                localDocumentUri = uriValue.takeIf { fallbackBridge != null }
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    error = error.message ?: "Unable to start local playback.",
                    playingPath = if (fallbackBridge != null) uriValue else null,
                    localPlayback = fallbackBridge != null,
                    directLocalFallback = false,
                )
                if (fallbackBridge != null) {
                    persist { preferences.addRecentLocalUri(uriValue) }
                }
            }
        }
    }

    fun openFile(file: LibraryNode) {
        if (file.type != LibraryNode.Type.FILE || mutableUi.value.busy) return
        viewModelScope.launch {
            mutableUi.value = mutableUi.value.copy(busy = true, error = null, playingPath = file.path)
            val currentController = requireNotNull(controller)
            runCatching {
                // Setting playingPath asks the Activity to enter sensor
                // landscape. Wait for the recreated Compose surface to report
                // its real pixels before negotiating the server output size.
                val landscape = withTimeoutOrNull(5_000) {
                    ui.first { it.display.width > it.display.height }
                }
                checkNotNull(landscape) { "Timed out waiting for the landscape player surface." }
                val endpoint = currentController.preparePlayback(
                    path = file.path,
                    display = landscape.display,
                    requestedModel = landscape.selectedModel,
                    qualityTier = landscape.qualityTier,
                    fitMode = landscape.fitMode,
                    resizeAlgorithm = landscape.resizeAlgorithm.ifEmpty { null },
                )
                applyResumePoint(currentController, endpoint, "server:${file.path}")
            }
                .onSuccess { endpoint ->
                    AppLog.i(TAG, "opened server file '${file.path.substringAfterLast('/')}' session=${endpoint.session.sessionId} model=${endpoint.model} tier=${endpoint.qualityTier} out=${endpoint.session.downlinkWidth}x${endpoint.session.downlinkHeight} epoch=${endpoint.session.epoch}")
                    activeOrigin = PlaybackOrigin.ServerFile(file.path)
                    warningDismissed = false
                    reconnectExhausted = false
                    sessionStartedAt = Instant.now()
                    mutableUi.value = mutableUi.value.copy(
                        busy = false,
                        endpoint = endpoint.localUrl,
                        session = endpoint.session,
                        selectedModel = endpoint.model,
                        sessionDescription =
                            "${endpoint.model} · ${endpoint.qualityTier} · " +
                                "${endpoint.session.downlinkWidth}×${endpoint.session.downlinkHeight}",
                    )
                    subtitlePreferenceAppliedSession = null
                    persist {
                        preferences.setModel(endpoint.model)
                        preferences.addRecent(file.path)
                    }
                    playerEngine.setPanscan(0.0)
                    playerEngine.load(endpoint.localUrl, endpoint.originalMediaUrl)
                    currentController.startServerPlayback()
                    startMetrics(currentController)
                }
                .onFailure { error ->
                    mutableUi.value = mutableUi.value.copy(
                        busy = false,
                        error = error.message,
                        playingPath = null,
                    )
                }
        }
    }

    fun togglePaused() {
        val next = !mutableUi.value.paused
        runCatching {
            if (!mutableUi.value.directLocalFallback) requireNotNull(controller).setPaused(next)
            playerEngine.setPaused(next)
        }.onSuccess {
            mutableUi.value = mutableUi.value.copy(paused = next)
        }.onFailure { error ->
            mutableUi.value = mutableUi.value.copy(error = error.message)
        }
    }

    fun cycleAudioTrack() {
        val tracks = mutableUi.value.tracks.filter { it.type == MpvTrack.Type.AUDIO }
        if (tracks.isEmpty()) return
        val selected = tracks.indexOfFirst { it.selected }
        playerEngine.selectAudioTrack(tracks[(selected + 1).mod(tracks.size)].id)
    }

    fun cycleSubtitleTrack() {
        val tracks = mutableUi.value.tracks.filter { it.type == MpvTrack.Type.SUBTITLE }
        if (tracks.isEmpty()) return
        val selected = tracks.indexOfFirst { it.selected }
        val next = if (selected < 0) tracks.first().id
        else if (selected == tracks.lastIndex) null
        else tracks[selected + 1].id
        selectSubtitleTrack(next)
    }

    fun selectAudioTrack(id: Int) {
        playerEngine.selectAudioTrack(id)
    }

    fun selectSubtitleTrack(id: Int?) {
        playerEngine.selectSubtitleTrack(id)
        val enabled = id != null
        val preference = mutableUi.value.tracks.firstOrNull { it.id == id }?.preferenceKey
            ?: mutableUi.value.preferredSubtitle
        mutableUi.value = mutableUi.value.copy(
            subtitlesEnabled = enabled,
            preferredSubtitle = preference,
        )
        persist {
            preferences.setSubtitlesEnabled(enabled)
            if (id != null) preferences.setPreferredSubtitle(preference)
        }
    }

    fun adjustAudioDelay(deltaSeconds: Double) {
        val next = mutableUi.value.mpvMetrics.audioDelaySeconds + deltaSeconds
        playerEngine.setAudioDelay(next)
        mutableUi.update { it.copy(mpvMetrics = it.mpvMetrics.copy(audioDelaySeconds = next)) }
    }

    fun adjustSubtitleDelay(deltaSeconds: Double) {
        val next = mutableUi.value.mpvMetrics.subtitleDelaySeconds + deltaSeconds
        playerEngine.setSubtitleDelay(next)
        mutableUi.update { it.copy(mpvMetrics = it.mpvMetrics.copy(subtitleDelaySeconds = next)) }
    }

    fun seekRelative(seconds: Double) {
        seekTo((mutableUi.value.mpvMetrics.positionSeconds + seconds).coerceAtLeast(0.0))
    }

    fun previewSeek(seconds: Double) {
        mutableUi.value = mutableUi.value.copy(seekPreviewSeconds = seconds)
    }

    fun commitSeek() {
        mutableUi.value.seekPreviewSeconds?.let(::seekTo)
    }

    fun cancelSeekPreview() {
        mutableUi.value = mutableUi.value.copy(seekPreviewSeconds = null)
    }

    fun seekTo(seconds: Double) {
        val prior = seekJob
        seekJob = viewModelScope.launch {
            prior?.cancelAndJoin()
            if (mutableUi.value.directLocalFallback) {
                val duration = mutableUi.value.mpvMetrics.durationSeconds
                val target = seconds.coerceIn(0.0, duration.takeIf { it > 0 } ?: Double.MAX_VALUE)
                playerEngine.seekDirect(target)
                mutableUi.value = mutableUi.value.copy(seekPreviewSeconds = null)
                return@launch
            }
            val currentController = controller ?: return@launch
            val session = mutableUi.value.session ?: return@launch
            val timeBase = session.timeBase ?: run {
                mutableUi.value = mutableUi.value.copy(error = "Server did not provide the source time base.")
                return@launch
            }
            val targetSeconds = seconds.coerceIn(0.0, session.durationSeconds ?: Double.MAX_VALUE)
            val targetPts = (targetSeconds / timeBase.value).roundToLong()
            mutableUi.value = mutableUi.value.copy(seeking = true, seekPreviewSeconds = null)
            AppLog.i(TAG, "seek to %.1fs".format(targetSeconds))
            try {
                currentController.expectPlayerReload()
                playerEngine.prepareReload()
                val endpoint = currentController.seek(targetPts)
                // stop -> retire old loopback/queue -> settle -> loadfile.
                // Never pass start=: absolute Matroska PTS remain authoritative.
                delay(150)
                playerEngine.load(endpoint.localUrl, endpoint.originalMediaUrl)
                mutableUi.value = mutableUi.value.copy(
                    endpoint = endpoint.localUrl,
                    session = endpoint.session,
                    seeking = false,
                )
            } catch (timeout: TimeoutCancellationException) {
                mutableUi.value = mutableUi.value.copy(seeking = false, error = timeout.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableUi.value = mutableUi.value.copy(seeking = false, error = error.message)
            }
        }
    }

    fun closePlayback() {
        AppLog.i(TAG, "close playback at %.1fs".format(mutableUi.value.mpvMetrics.positionSeconds))
        viewModelScope.launch {
            val host = mutableUi.value.host
            val port = mutableUi.value.port.toIntOrNull() ?: 8590
            mutableUi.value = mutableUi.value.copy(busy = true, endpoint = null, playingPath = null)
            activeOrigin = null
            reconnectExhausted = false
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            restartJob?.cancelAndJoin()
            restartJob = null
            seekJob?.cancelAndJoin()
            seekJob = null
            metricsJob?.cancelAndJoin()
            metricsJob = null
            playerEngine.stop()
            stopSystemMediaIntegration()
            subtitlePreferenceAppliedSession = null
            disposeController()
            withContext(Dispatchers.IO) { localDocumentServer?.close() }
            localDocumentServer = null
            localDocumentUri = null
            mutableUi.value = mutableUi.value.copy(
                localPlayback = false,
                directLocalFallback = false,
                reconnecting = null,
                performanceWarning = null,
            )
            connectInternal(host, port)
        }
    }

    fun playLocalFallback() {
        val bridge = localDocumentServer ?: return
        val position = mutableUi.value.mpvMetrics.positionSeconds
        AppLog.i(TAG, "direct local fallback at %.1fs".format(position))
        viewModelScope.launch {
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            restartJob?.cancelAndJoin()
            restartJob = null
            metricsJob?.cancelAndJoin()
            metricsJob = null
            seekJob?.cancelAndJoin()
            seekJob = null
            playerEngine.stop()
            withContext(Dispatchers.IO) { disposeController() }
            delay(100)
            playerEngine.loadDirect(bridge.url, position)
            mutableUi.value = mutableUi.value.copy(
                endpoint = bridge.url,
                error = null,
                busy = false,
                directLocalFallback = true,
                paused = false,
                reconnecting = null,
                performanceWarning = null,
            )
            startMetrics(null)
        }
    }

    fun retry() {
        viewModelScope.launch {
            activeOrigin = null
            reconnectExhausted = false
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            restartJob?.cancelAndJoin()
            restartJob = null
            metricsJob?.cancelAndJoin()
            metricsJob = null
            seekJob?.cancelAndJoin()
            seekJob = null
            playerEngine.stop()
            stopSystemMediaIntegration()
            withContext(Dispatchers.IO) { localDocumentServer?.close() }
            localDocumentServer = null
            localDocumentUri = null
            mutableUi.value = mutableUi.value.copy(endpoint = null, playingPath = null, error = null)
            mutableUi.value = mutableUi.value.copy(
                localPlayback = false,
                directLocalFallback = false,
                reconnecting = null,
                performanceWarning = null,
            )
            val host = mutableUi.value.host.trim()
            val port = mutableUi.value.port.toIntOrNull() ?: 8590
            connectInternal(host, port)
        }
    }

    private fun collectController(value: RelaySessionController) {
        controllerCollectors?.cancel()
        controllerCollectors = viewModelScope.launch {
            launch {
                value.state.collectLatest { state ->
                    AppLog.d(TAG, "controller state=$state")
                    mutableUi.update { it.copy(sessionState = state) }
                }
            }
            launch {
                value.stats.collectLatest { stats ->
                    mutableUi.update { it.copy(transportStats = stats) }
                }
            }
            launch {
                value.failure.collectLatest { failure ->
                    if (failure != null) {
                        AppLog.e(TAG, "controller failure ${failure.exceptionType} (${failure.kind}): ${failure.summary}")
                        if (!maybeAutoResume(failure)) {
                            mutableUi.update {
                                it.copy(error = failureMessage(failure), busy = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun failureMessage(failure: FailureDetail): String =
        if (failure.summary.isBlank()) failure.kind.label
        else "${failure.kind.label} — ${failure.summary}"

    /**
     * Starts the automatic reconnect/resume loop when the failure is a
     * transient network/server condition during relay playback. Returns false
     * when the failure should surface as an ordinary error instead.
     */
    private fun maybeAutoResume(failure: FailureDetail): Boolean {
        if (reconnectJob?.isActive == true) return true // the loop owns the UI
        // A spent budget must stay spent: the last attempt's own trailing
        // failure event must not arm a fresh loop.
        if (reconnectExhausted) return false
        val state = mutableUi.value
        val origin = activeOrigin ?: return false
        if (!state.autoResume || state.directLocalFallback) return false
        if (!failure.kind.recoverable || state.playingPath == null) return false
        beginAutoResume(origin, failure)
        return true
    }

    private fun beginAutoResume(origin: PlaybackOrigin, failure: FailureDetail) {
        val position = mutableUi.value.mpvMetrics.positionSeconds
        AppLog.w(TAG, "auto-resume starting: ${failure.kind} at %.1fs".format(position))
        reconnectJob = viewModelScope.launch {
            // Freeze playback so the resume position cannot drift: the local
            // audio bridge outlives the relay session and would otherwise keep
            // the audio clock running under the recovery UI.
            runCatching { playerEngine.setPaused(true) }
            metricsJob?.cancelAndJoin()
            metricsJob = null
            seekJob?.cancelAndJoin()
            seekJob = null
            val policy = ReconnectPolicy()
            val startedAt = SystemClock.elapsedRealtime()
            var attempt = 1
            var lastError = failure.summary
            while (true) {
                mutableUi.update {
                    it.copy(
                        error = null,
                        busy = false,
                        reconnecting = ReconnectStatus(attempt, policy.maxAttempts, failure.kind.label),
                    )
                }
                awaitRetryWindow(policy.delayBeforeAttempt(attempt))
                AppLog.i(TAG, "auto-resume attempt $attempt")
                try {
                    restartPlayback(origin, position)
                    resumeCount += 1
                    AppLog.i(TAG, "auto-resume succeeded on attempt $attempt")
                    mutableUi.update { it.copy(reconnecting = null, error = null) }
                    return@launch
                } catch (timeout: TimeoutCancellationException) {
                    // A timeout is a failed attempt, not this loop being
                    // cancelled — TimeoutCancellationException extends
                    // CancellationException.
                    lastError = timeout.message ?: lastError
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastError = error.message ?: lastError
                }
                attempt += 1
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (!policy.shouldAttempt(attempt, elapsed, failure.kind)) break
            }
            reconnectExhausted = true
            AppLog.e(TAG, "auto-resume gave up after ${attempt - 1} attempts ($lastError)")
            mutableUi.update {
                it.copy(
                    reconnecting = null,
                    error = "${failure.kind.label} — automatic reconnect gave up ($lastError)",
                )
            }
        }
    }

    /** Waits out the backoff delay, returning early if a network comes up. */
    private suspend fun awaitRetryWindow(delayMillis: Long) {
        val snapshot = networkEpoch.value
        withTimeoutOrNull(delayMillis) { networkEpoch.first { it != snapshot } }
    }

    /**
     * Rebuilds the control connection and the active session at the given
     * position with the current UI settings. Shared by automatic resume and
     * mid-play model/quality/framing changes.
     */
    private suspend fun restartPlayback(origin: PlaybackOrigin, positionSeconds: Double) {
        playerEngine.stop()
        withContext(Dispatchers.IO) { disposeController() }
        val state = mutableUi.value
        val host = state.host.trim()
        val port = state.port.toIntOrNull() ?: 8590
        val next = RelaySessionController(host, port)
        controller = next
        collectController(next)
        val connected = next.connect(state.display)
        val model = state.selectedModel.takeIf { selected ->
            connected.capabilities.models.any { it.name == selected }
        } ?: connected.capabilities.phaseOneModel
        val androidQualities = connected.capabilities.qualityOptions
            .filter { it.androidSupported && it.id in RelaySessionController.ANDROID_HEVC_TIERS }
        val tier = state.qualityTier.takeIf { selected ->
            androidQualities.any { it.id == selected }
        } ?: androidQualities.firstOrNull()?.id ?: "lossless-hevc"
        mutableUi.update {
            it.copy(capabilities = connected.capabilities, libraryRoot = connected.root)
        }
        val endpoint = when (origin) {
            is PlaybackOrigin.ServerFile -> next.preparePlayback(
                path = origin.path,
                display = state.display,
                requestedModel = model,
                qualityTier = tier,
                fitMode = state.fitMode,
                resizeAlgorithm = state.resizeAlgorithm.ifEmpty { null },
            )
            is PlaybackOrigin.LocalDocument -> {
                val uri = origin.uriValue.toUri()
                val source = withContext(Dispatchers.IO) {
                    AndroidMediaSource.open(getApplication(), uri)
                }
                val bridge = localDocumentServer?.takeIf { localDocumentUri == origin.uriValue }
                    ?: withContext(Dispatchers.IO) {
                        LocalDocumentHttpServer(getApplication(), uri)
                    }.also { fresh ->
                        withContext(Dispatchers.IO) { localDocumentServer?.close() }
                        localDocumentServer = fresh
                        localDocumentUri = origin.uriValue
                    }
                next.prepareLocalPlayback(
                    source = source,
                    originalMediaUrl = bridge.url,
                    display = state.display,
                    requestedModel = model,
                    qualityTier = tier,
                    fitMode = state.fitMode,
                    resizeAlgorithm = state.resizeAlgorithm.ifEmpty { null },
                )
            }
        }
        var finalEndpoint = endpoint
        val timeBase = endpoint.session.timeBase
        if (positionSeconds > 0.5 && timeBase != null) {
            finalEndpoint = next.seek((positionSeconds / timeBase.value).roundToLong())
        }
        sessionStartedAt = Instant.now()
        subtitlePreferenceAppliedSession = null
        warningDismissed = false
        reconnectExhausted = false
        mutableUi.update {
            it.copy(
                endpoint = finalEndpoint.localUrl,
                session = finalEndpoint.session,
                selectedModel = finalEndpoint.model,
                qualityTier = finalEndpoint.qualityTier,
                paused = false,
                seeking = false,
                busy = false,
                error = null,
                performanceWarning = null,
                sessionDescription =
                    "${finalEndpoint.model} · ${finalEndpoint.qualityTier} · " +
                        "${finalEndpoint.session.downlinkWidth}×${finalEndpoint.session.downlinkHeight}",
            )
        }
        playerEngine.setPanscan(0.0)
        playerEngine.load(finalEndpoint.localUrl, finalEndpoint.originalMediaUrl)
        playerEngine.setPaused(false)
        next.startServerPlayback()
        startMetrics(next)
    }

    /** Applies changed model/quality/framing settings to the active session. */
    private fun maybeRestartActiveSession() {
        val origin = activeOrigin ?: return
        val state = mutableUi.value
        if (state.playingPath == null || state.directLocalFallback) return
        if (reconnectJob?.isActive == true) return
        val prior = restartJob
        restartJob = viewModelScope.launch {
            prior?.cancelAndJoin()
            seekJob?.cancelAndJoin()
            seekJob = null
            metricsJob?.cancelAndJoin()
            metricsJob = null
            val position = mutableUi.value.mpvMetrics.positionSeconds
            AppLog.i(TAG, "restarting session for changed playback settings at %.1fs".format(position))
            mutableUi.update {
                it.copy(reconnecting = ReconnectStatus(1, 1, "Applying playback settings"), error = null)
            }
            try {
                restartPlayback(origin, position)
                mutableUi.update { it.copy(reconnecting = null) }
            } catch (timeout: TimeoutCancellationException) {
                mutableUi.update {
                    it.copy(reconnecting = null, error = timeout.message ?: "Applying the new settings timed out.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableUi.update {
                    it.copy(reconnecting = null, error = error.message ?: "Unable to apply the new settings.")
                }
            }
        }
    }

    /** Creates the MediaSession, takes audio focus, and raises the service. */
    private fun startSystemMediaIntegration() {
        if (mediaSession == null) {
            mediaSession = MediaSession(getApplication(), "upscale-relay").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = resumeForSystem()

                    override fun onPause() = pauseForSystem()

                    override fun onSeekTo(pos: Long) {
                        seekTo(pos / 1000.0)
                    }

                    override fun onFastForward() = seekRelative(10.0)

                    override fun onRewind() = seekRelative(-10.0)

                    override fun onStop() = closePlayback()
                })
                isActive = true
            }
            PlaybackBridge.sessionToken = mediaSession?.sessionToken
        }
        // Mark the bridge active before the service starts: its collector
        // stops the service on an inactive snapshot, and the first metrics
        // tick that would publish one may not have run yet.
        PlaybackBridge.snapshot.value = PlaybackBridge.Snapshot(
            active = true,
            title = mutableUi.value.playingPath?.substringAfterLast('/') ?: "",
            playing = !mutableUi.value.paused,
        )
        requestAudioFocus()
        runCatching { PlaybackService.start(getApplication()) }
    }

    private fun stopSystemMediaIntegration() {
        PlaybackBridge.snapshot.value = PlaybackBridge.Snapshot()
        runCatching { PlaybackService.stop(getApplication()) }
        abandonAudioFocus()
        mediaSession?.let { session ->
            session.isActive = false
            session.release()
        }
        mediaSession = null
        PlaybackBridge.sessionToken = null
        publishedMetadataKey = null
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val audio = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnFocusGain = false
                        pauseForSystem()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        if (!mutableUi.value.paused) {
                            resumeOnFocusGain = true
                            pauseForSystem()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (resumeOnFocusGain) {
                            resumeOnFocusGain = false
                            resumeForSystem()
                        }
                    }
                }
            }
            .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { request ->
            val audio =
                getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.abandonAudioFocusRequest(request)
        }
        focusRequest = null
        resumeOnFocusGain = false
    }

    private fun pauseForSystem() {
        val state = mutableUi.value
        if (state.playingPath != null && !state.paused) runCatching { togglePaused() }
    }

    private fun resumeForSystem() {
        val state = mutableUi.value
        if (state.playingPath != null && state.paused) runCatching { togglePaused() }
    }

    /**
     * Applies a stored resume point to a freshly opened session by seeking
     * before mpv attaches. Points near the end (credits) or too close to the
     * start are ignored.
     */
    private suspend fun applyResumePoint(
        controller: RelaySessionController,
        endpoint: PlaybackEndpoint,
        key: String,
    ): PlaybackEndpoint {
        val saved = playbackPositions[key] ?: return endpoint
        val duration = endpoint.session.durationSeconds ?: return endpoint
        val timeBase = endpoint.session.timeBase ?: return endpoint
        if (saved < RESUME_MIN_SECONDS || saved > duration - RESUME_END_WINDOW_SECONDS) {
            return endpoint
        }
        AppLog.i(TAG, "resuming at saved position %.1fs".format(saved))
        return controller.seek((saved / timeBase.value).roundToLong())
    }

    /** Persists the watch position (throttled); near the end it clears it. */
    private fun maybeSaveProgress(mpv: MpvMetrics) {
        val origin = activeOrigin ?: return
        val state = mutableUi.value
        if (state.playingPath == null || state.seeking) return
        if (reconnectJob?.isActive == true || restartJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressSaveAt < PROGRESS_SAVE_INTERVAL_MILLIS) return
        val position = mpv.positionSeconds
        if (position < RESUME_MIN_SECONDS) return
        lastProgressSaveAt = now
        val key = progressKey(origin)
        val duration = state.session?.durationSeconds ?: mpv.durationSeconds
        if (duration > 0 && position > duration - RESUME_END_WINDOW_SECONDS) {
            persist { preferences.clearPlaybackPosition(key) }
        } else {
            persist { preferences.setPlaybackPosition(key, position) }
        }
    }

    /** Mirrors playback into the MediaSession and the notification bridge. */
    private fun updateMediaSession(mpv: MpvMetrics) {
        val session = mediaSession ?: return
        val state = mutableUi.value
        val title = state.playingPath?.substringAfterLast('/') ?: return
        val durationMillis =
            (((state.session?.durationSeconds ?: mpv.durationSeconds)) * 1000).roundToLong()
        val metadataKey = "$title|$durationMillis"
        if (metadataKey != publishedMetadataKey) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Upscale Relay")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMillis)
                    .build(),
            )
            publishedMetadataKey = metadataKey
        }
        val playing = !state.paused
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    (mpv.positionSeconds * 1000).roundToLong(),
                    if (playing) 1f else 0f,
                )
                .build(),
        )
        PlaybackBridge.snapshot.value =
            PlaybackBridge.Snapshot(active = true, title = title, playing = playing)
    }

    private fun startMetrics(value: RelaySessionController?) {
        metricsJob?.cancel()
        startSystemMediaIntegration()
        lastReceivedBytes = 0L
        lastReceiveChangeAt = SystemClock.elapsedRealtime()
        lastPausedForCache = false
        rebufferTimestamps = emptyList()
        metricsStartedAt = SystemClock.elapsedRealtime()
        decoderDropsWindow = SystemClock.elapsedRealtime() to 0L
        metricsJob = viewModelScope.launch {
            while (true) {
                val metrics = playerEngine.snapshot()
                val tracks = withContext(Dispatchers.IO) { playerEngine.trackSnapshot() }
                val sessionId = mutableUi.value.session?.sessionId
                val subtitles = tracks.filter { it.type == MpvTrack.Type.SUBTITLE }
                if (
                    sessionId != null &&
                    subtitlePreferenceAppliedSession != sessionId &&
                    subtitles.isNotEmpty()
                ) {
                    playerEngine.selectSubtitleTrack(
                        if (mutableUi.value.subtitlesEnabled) {
                            subtitles.firstOrNull {
                                it.preferenceKey == mutableUi.value.preferredSubtitle
                            }?.id ?: subtitles.first().id
                        } else null,
                    )
                    subtitlePreferenceAppliedSession = sessionId
                }
                value?.updatePlayerBuffer(
                    PlayerBufferSnapshot(metrics.cacheDurationMillis, metrics.bitrateBitsPerSecond),
                )
                mutableUi.value = mutableUi.value.copy(mpvMetrics = metrics, tracks = tracks)
                metricsTickCount += 1
                if (AppLog.active && metricsTickCount % 10 == 0L) {
                    val transport = value?.stats?.value
                    AppLog.fileOnly(
                        'I',
                        "telem",
                        "pos=%.1fs %s/%s hwdec=%s drops=%d/%d av=%.1fms cache=%dms queue=%dMB rx=%.0fMbps rebuf=%s".format(
                            metrics.positionSeconds,
                            mutableUi.value.sessionState,
                            mutableUi.value.playerState,
                            metrics.hardwareDecoder.ifEmpty { "-" },
                            metrics.decoderDroppedFrames,
                            metrics.outputDroppedFrames,
                            metrics.avSyncSeconds * 1000,
                            metrics.cacheDurationMillis,
                            (transport?.queuedBytes ?: 0) / (1024 * 1024),
                            transport?.averageMegabitsPerSecond ?: 0.0,
                            metrics.pausedForCache,
                        ),
                    )
                }
                updateMediaSession(metrics)
                maybeSaveProgress(metrics)
                if (value != null) handleWatchdogs(value.stats.value, metrics)
                writeDiagnostics(value?.stats?.value ?: TransportStats(), metrics)
                delay(1_000)
            }
        }
    }

    /**
     * Client-side stall detection plus capability/sustain warnings, evaluated
     * once per metrics tick during relay playback.
     */
    private fun handleWatchdogs(transport: TransportStats, mpv: MpvMetrics) {
        val now = SystemClock.elapsedRealtime()
        val state = mutableUi.value

        // Stalled connection: mpv is starved and the downlink byte counter has
        // not moved. A healthy watermark pause keeps mpv's cache full, so
        // requiring paused_for_cache avoids false positives.
        if (transport.receivedBytes != lastReceivedBytes) {
            lastReceivedBytes = transport.receivedBytes
            lastReceiveChangeAt = now
        } else if (
            now - lastReceiveChangeAt > STALL_TIMEOUT_MILLIS &&
            state.sessionState == SessionState.PLAYING &&
            !state.paused && mpv.pausedForCache &&
            reconnectJob?.isActive != true && restartJob?.isActive != true
        ) {
            lastReceiveChangeAt = now
            AppLog.w(TAG, "stall watchdog: no media for ${STALL_TIMEOUT_MILLIS / 1000}s while PLAYING")
            val failure = FailureDetail(
                summary = "no media received for ${STALL_TIMEOUT_MILLIS / 1000} s",
                exceptionType = MediaStalledException::class.qualifiedName.orEmpty(),
                kind = FailureKind.MEDIA_STALLED,
            )
            if (!maybeAutoResume(failure)) {
                mutableUi.update { it.copy(error = failureMessage(failure)) }
            }
        }

        // Server-sustain warning: repeated real rebuffers in a short window.
        // Session warm-up (empty cache before the first rendered frame, cold
        // pipeline right after a restart) must not count as a rebuffer.
        val steadyState = state.sessionState == SessionState.PLAYING &&
            now - metricsStartedAt > 15_000
        if (steadyState && mpv.pausedForCache && !lastPausedForCache) {
            rebufferTimestamps = (rebufferTimestamps + now).filter { now - it < 90_000 }
            if (rebufferTimestamps.size >= 2 && state.performanceWarning == null && !warningDismissed) {
                AppLog.w(TAG, "sustain warning: repeated rebuffers with ${state.selectedModel}/${state.qualityTier}")
                mutableUi.update {
                    it.copy(
                        performanceWarning =
                            "The server is not keeping up with ${state.selectedModel} at " +
                                "${state.qualityTier}. A smaller model or lower quality should play smoothly.",
                    )
                }
            }
        }
        lastPausedForCache = mpv.pausedForCache

        // Device-sustain warning: sustained hardware decoder drops.
        if (now - decoderDropsWindow.first >= 60_000) {
            decoderDropsWindow = now to mpv.decoderDroppedFrames
        } else if (
            mpv.decoderDroppedFrames - decoderDropsWindow.second >= 60 &&
            state.performanceWarning == null && !warningDismissed
        ) {
            AppLog.w(TAG, "sustain warning: decoder drops at ${state.qualityTier}")
            mutableUi.update {
                it.copy(
                    performanceWarning =
                        "This tablet's decoder is dropping frames at ${state.qualityTier}. " +
                            "A lower-bandwidth quality should play more smoothly.",
                )
            }
        }
    }

    private suspend fun writeDiagnostics(transport: TransportStats, mpv: MpvMetrics) =
        withContext(Dispatchers.IO) {
            val state = mutableUi.value
            val report = buildJsonObject {
                put("generated_at", Instant.now().toString())
                put("session_started_at", sessionStartedAt?.toString() ?: "")
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_sdk", Build.VERSION.SDK_INT)
                put("build_fingerprint", Build.FINGERPRINT)
                put("mpv_version", playerVersions["mpv"].orEmpty())
                put("ffmpeg_version", playerVersions["ffmpeg"].orEmpty())
                put("server", "${state.host}:${state.port}")
                put("file", state.playingPath ?: "")
                put("source", if (state.localPlayback) "local" else "server_file")
                put("direct_local_fallback", state.directLocalFallback)
                put("session_state", state.sessionState.name)
                put("player_state", state.playerState.name)
                put("hwdec_current", mpv.hardwareDecoder)
                put("codec", mpv.codec)
                put("audio_codec", mpv.audioCodec)
                put("coded_width", mpv.codedWidth)
                put("coded_height", mpv.codedHeight)
                put("fps", mpv.framesPerSecond)
                put("bitrate_bps", mpv.bitrateBitsPerSecond)
                put("mpv_cache_ms", mpv.cacheDurationMillis)
                put("output_dropped_frames", mpv.outputDroppedFrames)
                put("decoder_dropped_frames", mpv.decoderDroppedFrames)
                put("paused_for_cache", mpv.pausedForCache)
                put("paused", mpv.paused)
                put("position_s", mpv.positionSeconds)
                put("duration_s", mpv.durationSeconds)
                put("source_duration_s", state.session?.durationSeconds ?: 0.0)
                put("audio_pts_s", mpv.audioPtsSeconds)
                put("avsync_s", mpv.avSyncSeconds)
                put("audio_delay_s", mpv.audioDelaySeconds)
                put("subtitle_delay_s", mpv.subtitleDelaySeconds)
                put("cache_buffering_percent", mpv.cacheBufferingPercent)
                put("seeking", mpv.seeking)
                put("core_idle", mpv.coreIdle)
                put("received_bytes", transport.receivedBytes)
                put("received_packets", transport.receivedPackets)
                put("average_mbps", transport.averageMegabitsPerSecond)
                put("queue_bytes", transport.queuedBytes)
                put("queue_packets", transport.queuedPackets)
                put("reported_buffer_ms", transport.reportedBufferMillis)
                put("selected_model", state.selectedModel)
                put("quality_tier", state.qualityTier)
                put("fit_mode", state.fitMode)
                put("resize_algorithm", state.session?.resizeAlgorithm ?: "server-default")
                put("deband_enabled", state.debandEnabled)
                put("gestures_enabled", state.gesturesEnabled)
                put("auto_resume_enabled", state.autoResume)
                put("auto_resume_count", resumeCount)
                put("reconnecting", state.reconnecting != null)
                put("performance_warning", state.performanceWarning ?: "")
            }
            File(getApplication<Application>().filesDir, "phase4-latest.json").writeText(report.toString())
        }

    private suspend fun disposeController() {
        // A cancelled collector can still publish one last value until its
        // cancellation is observed.  Wait for it before installing a new
        // controller so an old FAILED state cannot overwrite a successful
        // retry's BROWSING state.
        controllerCollectors?.cancelAndJoin()
        controllerCollectors = null
        val current = controller
        controller = null
        if (current != null) {
            withTimeoutOrNull(5_000) { current.teardown() }
            current.close()
        }
    }

    override fun onCleared() {
        stopSystemMediaIntegration()
        PlaybackBridge.controls = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        discovery.stop()
        connectivityCallback?.let { callback ->
            val connectivity =
                getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        runBlocking(Dispatchers.IO) {
            reconnectJob?.cancelAndJoin()
            restartJob?.cancelAndJoin()
            metricsJob?.cancelAndJoin()
            seekJob?.cancelAndJoin()
            withTimeoutOrNull(3_000) { disposeController() }
            localDocumentServer?.close()
        }
        playerEngine.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "RelayAndroid"
        private const val STALL_TIMEOUT_MILLIS = 15_000L
        private const val PROGRESS_SAVE_INTERVAL_MILLIS = 5_000L
        private const val RESUME_MIN_SECONDS = 10.0
        private const val RESUME_END_WINDOW_SECONDS = 90.0
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}

enum class TabletDestination { SERVER, LOCAL, RECENT, SETTINGS }

data class RelayUiState(
    val host: String = "192.168.0.115",
    val port: String = "8590",
    val display: DisplaySize = DisplaySize(2960, 1848),
    val busy: Boolean = false,
    val error: String? = null,
    val sessionState: SessionState = SessionState.DISCONNECTED,
    val playerState: MpvPlaybackState = MpvPlaybackState.IDLE,
    val capabilities: Capabilities? = null,
    val libraryRoot: LibraryNode? = null,
    val currentDirectory: LibraryNode? = null,
    val directoryStack: List<LibraryNode> = emptyList(),
    val endpoint: String? = null,
    val session: SessionInfo? = null,
    val playingPath: String? = null,
    val sessionDescription: String = "",
    val transportStats: TransportStats = TransportStats(),
    val mpvMetrics: MpvMetrics = MpvMetrics(),
    val qualityTier: String = "lossless-hevc",
    val fitMode: String = "fit",
    val resizeAlgorithm: String = "",
    val debandEnabled: Boolean = false,
    val paused: Boolean = false,
    val seeking: Boolean = false,
    val seekPreviewSeconds: Double? = null,
    val tracks: List<MpvTrack> = emptyList(),
    val preferencesLoaded: Boolean = false,
    val destination: TabletDestination = TabletDestination.SERVER,
    val selectedLibraryNode: LibraryNode? = null,
    val selectedModel: String = "",
    val autoConnect: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    val preferredSubtitle: String = "",
    val diagnosticsVisible: Boolean = false,
    val gesturesEnabled: Boolean = true,
    val recentPaths: List<String> = emptyList(),
    val recentLocalUris: List<String> = emptyList(),
    val recentLocalRootUris: List<String> = emptyList(),
    val localDirectoryName: String? = null,
    val localEntries: List<LocalDocumentEntry> = emptyList(),
    val localCanGoUp: Boolean = false,
    val localPlayback: Boolean = false,
    val directLocalFallback: Boolean = false,
    val autoResume: Boolean = true,
    val reconnecting: ReconnectStatus? = null,
    val performanceWarning: String? = null,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val displayResampleSync: Boolean = false,
    val interpolationEnabled: Boolean = false,
    val interpolationScaler: String = "oversample",
    val backgroundPlayback: Boolean = true,
    val fileLoggingEnabled: Boolean = false,
    val logFileName: String? = null,
)

/** Live status of the automatic reconnect/resume loop shown in the player. */
data class ReconnectStatus(val attempt: Int, val maxAttempts: Int, val reason: String)

private val MpvTrack.preferenceKey: String get() = "$language\u001f$title"
