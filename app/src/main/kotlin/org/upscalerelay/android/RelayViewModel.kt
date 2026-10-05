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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.upscalerelay.client.FailureDetail
import org.upscalerelay.client.FailureKind
import org.upscalerelay.client.MediaStalledException
import org.upscalerelay.client.PlaybackEndpoint
import org.upscalerelay.client.PlayerBufferSnapshot
import org.upscalerelay.client.RelaySessionController
import org.upscalerelay.client.SessionState
import org.upscalerelay.client.TransportStats
import org.upscalerelay.client.classifyFailure
import org.upscalerelay.demux.AndroidMediaSource
import org.upscalerelay.demux.LocalDocumentHttpServer
import org.upscalerelay.demux.LocalDocumentBrowser
import org.upscalerelay.demux.LocalDocumentEntry
import org.upscalerelay.player.mpv.MpvMetrics
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.player.mpv.MpvPlayerEngine
import org.upscalerelay.player.mpv.MpvTrack
import org.upscalerelay.player.mpv.RelayAuxMode
import org.upscalerelay.player.mpv.RelayLoad
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.DisplaySize
import org.upscalerelay.protocol.LibraryNode
import org.upscalerelay.protocol.SessionInfo
import org.upscalerelay.protocol.SeekProgress
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class RelayViewModel(application: Application) : AndroidViewModel(application) {
    val playerEngine = MpvPlayerEngine(application)
    private val playerReady = CompletableDeferred<Unit>()
    private val mutableUi = MutableStateFlow(RelayUiState())
    val ui: StateFlow<RelayUiState> = mutableUi.asStateFlow()
    private var controller: RelaySessionController? = null
    private var controllerCollectors: Job? = null
    private var metricsJob: Job? = null
    private var seekJob: Job? = null
    private var openingJob: Job? = null
    private var closingJob: Job? = null
    // A failed release barrier must not be bypassed by an automatic retry.
    private var cleanupFailure: Throwable? = null
    private val disposalMutex = Mutex()
    private val loggingMutex = Mutex()
    private val actionErrors = CoroutineExceptionHandler { _, error ->
        AppLog.e(TAG, "playback action failed: ${error.message}")
        mutableUi.update { it.copy(busy = false, seeking = false, reconnecting = null, error = error.message) }
    }
    private var sessionStartedAt: Instant? = null
    private var playerVersions: Map<String, String> = emptyMap()
    private val preferences = AppPreferencesStore(application)
    private val attachmentCacheRoot = application.cacheDir.resolve("relay-attachments").toPath()
    private var autoConnectAttempted = false
    private var subtitlePreferenceAppliedSession: String? = null
    private var trackDiagnosticsLoggedSession: String? = null
    private var localDocumentServer: LocalDocumentHttpServer? = null
    private var localDocumentUri: String? = null
    private var localTreeUri: Uri? = null
    private var localDirectoryStack: List<Pair<Uri, String>> = emptyList()
    // Provider-order listing of the current local directory; the UI list is
    // re-derived from it whenever the sort preference changes.
    private var localEntriesRaw: List<LocalDocumentEntry> = emptyList()
    private var seekTargetSetAt = 0L
    private var autoAdvanceJob: Job? = null

    // The one quiet connect that follows an event — the browse connection
    // was seen to die, the app came to the foreground, a network appeared.
    // There is no timer and no attempt budget behind it: if it fails nothing
    // is said, and the next thing the user does that needs the server
    // connects on demand (connectionForAction) and reports for itself.
    private var quietConnectJob: Job? = null
    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // The cold-start connect that runs behind the cached listing. Like the
    // connect that follows leaving the player (closingJob), nothing shows
    // while it runs; an action that needs the server waits for both in
    // connectionForAction.
    private var backgroundConnectJob: Job? = null
    private var quietRefreshJob: Job? = null

    // "host:port" the listing on screen came from. A connect to the same
    // server keeps that listing up while it runs; any other server starts
    // from an empty screen.
    private var libraryOrigin: String? = null

    // The message of a connect the user asked for and did not get, while it
    // is the one on screen. It describes a condition, not an event, so the
    // next connect that succeeds takes it down.
    private var connectionError: String? = null

    // An open is on its first attempt and will reconnect and try again by
    // itself if the control socket turns out to be dead, so the failure
    // collector must not put that first failure on screen.
    private var openRetryPending = false
    private val libraryCacheFile = application.filesDir.resolve("library-cache.json")
    private var appInForeground = true

    // A recoverable failure that has not been shown to the user: recovery is
    // either running or waiting for the app to come back to the foreground.
    // It becomes an error banner only once recovery truly gives up, so a
    // tablet that slept long enough to lose the socket retries on wake
    // instead of greeting the user with a prompt.
    private var pendingFailure: FailureDetail? = null

    // Last browsed server-library directory, mirrored from DataStore so the
    // first connect after an app restart can restore it.
    private var persistedLibraryPath: String = ""

    // First-visible item index/offset per browser list (keyed "server:<path>"
    // or "local:<name>"), so leaving for the player and coming back — or
    // reconnecting — lands on the same spot instead of the top.
    private val listScrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun savedListScroll(key: String): Pair<Int, Int> = listScrollPositions[key] ?: (0 to 0)

    fun saveListScroll(key: String, index: Int, offset: Int) {
        listScrollPositions[key] = index to offset
    }

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
    private var lastDriftResyncAt = 0L
    private var rebufferTimestamps: List<Long> = emptyList()
    private var metricsStartedAt = 0L
    private var playbackPositions: Map<String, PlaybackProgress> = emptyMap()
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
        override fun onStart(owner: LifecycleOwner) {
            appInForeground = true
            // Sleeping kills the control socket. Whatever died while we were
            // away is re-made here — playback first, then the browse
            // connection. (When the failure is detected only after wake, the
            // failure collector routes into the same two calls.)
            if (!resumePendingPlayback("app foregrounded") &&
                !reconnectQuietly("app foregrounded")
            ) {
                // The connection survived, but the server's files may have
                // changed while the app was away.
                refreshLibraryQuietly()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            appInForeground = false
            // Explicit policy: with background playback off, leaving the app
            // pauses; with it on, the foreground service keeps audio running
            // while the detached Surface parks video in the null vo.
            val state = mutableUi.value
            if (!state.backgroundPlayback && state.playingPath != null && !state.paused &&
                state.reconnecting == null
            ) {
                runPlaybackCatching { togglePaused() }
            }
        }
    }
    private val bridgeControls = object : PlaybackBridge.Controls {
        override fun togglePlayPause() {
            viewModelScope.launch(actionErrors) { runPlaybackCatching { togglePaused() } }
        }

        override fun playbackSeekBy(seconds: Double) {
            viewModelScope.launch(actionErrors) { runPlaybackCatching { seekRelative(seconds) } }
        }

        override fun stopPlayback() {
            viewModelScope.launch(actionErrors) { runPlaybackCatching { closePlayback() } }
        }
    }

    private var metricsTickCount = 0L

    init {
        playerEngine.logSink = { level, line ->
            // mpv lines already reach logcat inside the engine.
            AppLog.fileOnly(if (level <= 20) 'E' else if (level <= 30) 'W' else 'I', "mpv", line)
        }
        viewModelScope.launch(actionErrors) {
            try {
                withContext(Dispatchers.IO) { playerEngine.initialize() }
                playerVersions = playerEngine.versionInfo()
                playerReady.complete(Unit)
            } catch (error: Throwable) {
                playerReady.completeExceptionally(error)
                throw error
            }
        }
        viewModelScope.launch(actionErrors) {
            playerEngine.state.collectLatest { state ->
                if (state == MpvPlaybackState.PLAYING) controller?.markRendering()
                if (state == MpvPlaybackState.ENDED) {
                    // mpv fires END_FILE for a true end-of-file *and* for a
                    // downlink that dies mid-file (e.g. the server dropping
                    // while the tablet sleeps). Only a genuine EOF near the
                    // duration may pin the entry at 100%; a mid-file END_FILE
                    // must keep the real position maybeSaveProgress last
                    // recorded, and the reconnect path takes it from there.
                    val reconnecting =
                        reconnectJob?.isActive == true || restartJob?.isActive == true
                    val duration = mutableUi.value.session?.durationSeconds
                        ?: mutableUi.value.mpvMetrics.durationSeconds
                    val position = mutableUi.value.mpvMetrics.positionSeconds
                    val atEnd = duration > 0 && position >= duration - RESUME_END_WINDOW_SECONDS
                    // Leaving the player stops mpv too; that is never the
                    // file reaching its end.
                    val leaving = mutableUi.value.playingPath == null
                    if (!reconnecting && !leaving && atEnd) {
                        AppLog.i(TAG, "playback ended (natural EOS)")
                        // A completed playthrough keeps a 100% entry (position
                        // == duration); resume ignores it, so the next open
                        // still starts from the beginning.
                        activeOrigin?.let { origin ->
                            persist {
                                preferences.setPlaybackPosition(progressKey(origin), duration, duration)
                            }
                        }
                    } else {
                        AppLog.i(
                            TAG,
                            "playback ended without EOS (pos=%.1f dur=%.1f reconnecting=%b); keeping saved progress"
                                .format(position, duration, reconnecting),
                        )
                    }
                }
                mutableUi.value = mutableUi.value.copy(playerState = state)
                if (state == MpvPlaybackState.ENDED) maybeAutoAdvance()
            }
        }
        val connectivity = connectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkEpoch.update { it + 1 }
                // A restored network is the moment to re-make whatever its
                // absence broke: Wi-Fi often re-associates seconds after the
                // wake-triggered connect has already failed.
                viewModelScope.launch(actionErrors) {
                    if (!resumePendingPlayback("network available")) {
                        reconnectQuietly("network available")
                    }
                }
            }
        }
        runPlaybackCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess { connectivityCallback = networkCallback }
        discovery.start()
        PlaybackBridge.controls = bridgeControls
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        viewModelScope.launch(actionErrors) {
            discovery.servers.collectLatest { servers ->
                mutableUi.update { it.copy(discoveredServers = servers) }
            }
        }
        viewModelScope.launch(actionErrors) {
            preferences.values.collectLatest { value ->
                // Still render settings and the initialization error if native
                // startup failed; a failed player must not strand the splash.
                runPlaybackCatching { playerReady.await() }
                val firstLoad = !mutableUi.value.preferencesLoaded
                // Read before the first frame is drawn, so the list the user
                // left is what the app opens on rather than a connect screen.
                val cachedLibrary = if (firstLoad && value.autoConnect) {
                    readLibraryCache("${value.host.trim()}:${value.port}")
                } else {
                    null
                }
                if (cachedLibrary != null) libraryOrigin = cachedLibrary.origin
                mutableUi.update { state ->
                    state.copy(
                        currentDirectory = cachedLibrary?.directory ?: state.currentDirectory,
                        directoryStack = cachedLibrary?.stack ?: state.directoryStack,
                        directoryCursorStack = cachedLibrary?.stack?.map { null }
                            ?: state.directoryCursorStack,
                        libraryRoot = cachedLibrary?.let { it.stack.firstOrNull() ?: it.directory }
                            ?: state.libraryRoot,
                        host = value.host,
                        port = value.port.toString(),
                        autoConnect = value.autoConnect,
                        autoPlayNext = value.autoPlayNext,
                        selectedModel = value.model,
                        qualityTier = value.qualityTier,
                        fitMode = value.fitMode,
                        resizeAlgorithm = value.resizeAlgorithm,
                        debandEnabled = value.debandEnabled,
                        subtitlesEnabled = value.subtitlesEnabled,
                        preferredSubtitle = value.preferredSubtitle,
                        diagnosticsVisible = value.diagnosticsVisible,
                        gesturesEnabled = value.gesturesEnabled,
                        librarySort = LibrarySort.entries.firstOrNull { it.name == value.librarySort }
                            ?: LibrarySort.NAME,
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
                        playbackProgress = value.playbackPositions,
                        playbackHistoryLimit = value.playbackHistoryLimit,
                        skipSeconds = value.skipSeconds,
                        preferencesLoaded = true,
                    )
                }
                playbackPositions = value.playbackPositions
                persistedLibraryPath = value.lastLibraryPath
                syncFileLogging(value.fileLoggingEnabled)
                playerEngine.setDeband(value.debandEnabled)
                playerEngine.setVideoSyncPreferences(
                    displayResample = value.displayResampleSync,
                    interpolation = value.interpolationEnabled,
                    scaler = value.interpolationScaler,
                )
                if (firstLoad && value.autoConnect && !autoConnectAttempted) {
                    autoConnectAttempted = true
                    connect(visible = cachedLibrary == null)
                }
            }
        }
        viewModelScope.launch(actionErrors) {
            // Identity, not equality: listings are replaced wholesale, and a
            // deep compare of two large trees on every metrics tick is waste.
            ui.map { it.currentDirectory to it.directoryStack }
                .distinctUntilChanged { old, new -> old.first === new.first && old.second === new.second }
                .collectLatest { (directory, stack) ->
                    val origin = libraryOrigin
                    if (directory == null || origin == null) return@collectLatest
                    delay(LIBRARY_CACHE_WRITE_DELAY_MILLIS)
                    writeLibraryCache(LibrarySnapshot(origin, directory, stack))
                }
        }
    }

    private suspend fun readLibraryCache(origin: String): LibrarySnapshot? =
        withContext(Dispatchers.IO) {
            runPlaybackCatching { LibraryCacheCodec.decode(libraryCacheFile.readText()) }.getOrNull()
        }?.takeIf { it.origin == origin }

    private suspend fun writeLibraryCache(snapshot: LibrarySnapshot) {
        withContext(Dispatchers.IO) {
            runPlaybackCatching {
                val text = LibraryCacheCodec.encode(snapshot)
                if (text == null) {
                    libraryCacheFile.delete()
                } else {
                    val pending = File(libraryCacheFile.path + ".tmp")
                    pending.writeText(text)
                    if (!pending.renameTo(libraryCacheFile)) {
                        libraryCacheFile.delete()
                        pending.renameTo(libraryCacheFile)
                    }
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
        viewModelScope.launch(Dispatchers.IO + actionErrors) {
            loggingMutex.withLock {
                if (enabled == mutableUi.value.fileLoggingEnabled) configureFileLogging(enabled)
            }
        }
    }

    private fun configureFileLogging(enabled: Boolean) {
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
        val version = runPlaybackCatching {
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

    fun setAutoPlayNext(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(autoPlayNext = value)
        persist { preferences.setAutoPlayNext(value) }
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

    /**
     * Long-press action: toggles the file between fully watched (100%) and
     * unwatched (0%). Files never played have no duration on record; equal
     * sentinel values still render as 100% and sit below the resume threshold,
     * and real values take over the next time the file plays.
     */
    fun markWatched(key: String) {
        val existing = playbackPositions[key]
        val duration = existing?.durationSeconds?.takeIf { it > 0 } ?: 1.0
        val target = if (isWatched(key)) 0.0 else duration
        persist { preferences.setPlaybackPosition(key, target, duration) }
    }

    /**
     * Whether the saved progress for a key reads as fully watched. Anything the
     * UI would round to 100% counts, so the watched toggle, the percentage on
     * the card, and auto-advance all agree on what "finished" means.
     */
    private fun isWatched(key: String): Boolean {
        val progress = playbackPositions[key] ?: return false
        if (progress.durationSeconds <= 0) return false
        return (progress.positionSeconds / progress.durationSeconds * 100).roundToInt() >= 100
    }

    /** Filename offered to the create-document picker for a backup. */
    fun suggestedBackupFileName(): String = BackupCodec.suggestedFileName(Instant.now())

    /** Writes every saved setting and the watch history to the chosen file. */
    fun exportData(target: Uri) {
        viewModelScope.launch(actionErrors) {
            mutableUi.update { it.copy(backupStatus = null) }
            runPlaybackCatching {
                val snapshot = preferences.snapshot()
                val text = BackupCodec.encode(snapshot, appVersionName(), Instant.now())
                withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver
                        .openOutputStream(target, "wt")
                        ?: throw IOException("The chosen location could not be opened for writing.")
                    stream.use { it.write(text.toByteArray()) }
                }
                snapshot
            }.onSuccess { snapshot ->
                AppLog.i(TAG, "exported backup (${snapshot.playbackPositions.size} history entries)")
                mutableUi.update {
                    it.copy(
                        backupStatus = BackupStatus(
                            "Exported all settings and ${snapshot.playbackPositions.size} " +
                                "watch-history ${entryWord(snapshot.playbackPositions.size)}.",
                        ),
                    )
                }
            }.onFailure { error ->
                AppLog.e(TAG, "backup export failed: ${error.message}")
                mutableUi.update {
                    it.copy(
                        backupStatus = BackupStatus(
                            error.message ?: "Could not write the backup file.",
                            failed = true,
                        ),
                    )
                }
            }
        }
    }

    /** Restores a backup file over the saved settings and watch history. */
    fun importData(source: Uri) {
        viewModelScope.launch(actionErrors) {
            mutableUi.update { it.copy(backupStatus = null) }
            runPlaybackCatching {
                val text = withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openInputStream(source)
                        ?: throw IOException("The chosen file could not be opened.")
                    // Bounded by hand: InputStream.readNBytes is API 33 and
                    // readBytes() would happily pull in a multi-gigabyte pick.
                    stream.use { input ->
                        val collected = ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            require(collected.size() + read <= BackupCodec.MAX_BYTES) {
                                "That file is too large to be a backup."
                            }
                            collected.write(chunk, 0, read)
                        }
                        String(collected.toByteArray(), Charsets.UTF_8)
                    }
                }
                val restored = BackupCodec.decode(text, preferences.snapshot())
                preferences.importAll(restored)
                restored
            }.onSuccess { restored ->
                AppLog.i(TAG, "imported backup (${restored.playbackPositions.size} history entries)")
                mutableUi.update {
                    it.copy(
                        backupStatus = BackupStatus(
                            "Restored all settings and ${restored.playbackPositions.size} " +
                                "watch-history ${entryWord(restored.playbackPositions.size)}. " +
                                "Reconnect to apply the server details.",
                        ),
                    )
                }
            }.onFailure { error ->
                AppLog.e(TAG, "backup import failed: ${error.message}")
                mutableUi.update {
                    it.copy(
                        backupStatus = BackupStatus(
                            error.message ?: "Could not read the backup file.",
                            failed = true,
                        ),
                    )
                }
            }
        }
    }

    fun dismissBackupStatus() {
        mutableUi.update { it.copy(backupStatus = null) }
    }

    private fun entryWord(count: Int) = if (count == 1) "entry" else "entries"

    private fun appVersionName(): String {
        val app = getApplication<Application>()
        return runPlaybackCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    fun setPlaybackHistoryLimit(value: Int) {
        val limit = value.coerceIn(1, MAX_POSITIONS_LIMIT)
        mutableUi.update { it.copy(playbackHistoryLimit = limit) }
        // Excess entries are trimmed lazily: decode caps at the limit, and the
        // next position save persists the trimmed list.
        persist { preferences.setPlaybackHistoryLimit(limit) }
    }

    fun setSkipSeconds(value: Int) {
        val seconds = value.coerceIn(1, MAX_SKIP_SECONDS)
        mutableUi.update { it.copy(skipSeconds = seconds) }
        persist { preferences.setSkipSeconds(seconds) }
    }

    /**
     * Seeks forward (+1) or back (-1) by the configured skip amount (default
     * 1:25, e.g. past an opening). Shared by the player buttons, keyboard and
     * media-session controls.
     */
    fun skip(direction: Int) {
        if (direction == 0) return
        val seconds = mutableUi.value.skipSeconds.toDouble()
        seekRelative(if (direction > 0) seconds else -seconds)
    }

    fun setLibrarySort(value: LibrarySort) {
        if (mutableUi.value.librarySort == value) return
        mutableUi.update { it.copy(librarySort = value) }
        mutableUi.update { it.copy(localEntries = sortedLocalEntries(localEntriesRaw)) }
        persist { preferences.setLibrarySort(value.name) }
        refreshServerDirectoryForSort()
    }

    /**
     * The GET /library sort key for the current preference, or null when the
     * server predates sorting (its default name order applies).
     */
    private fun serverSortParam(): String? {
        val keys = mutableUi.value.capabilities?.librarySortKeys.orEmpty()
        val wanted = when (mutableUi.value.librarySort) {
            LibrarySort.NAME -> "name"
            LibrarySort.DATE -> "mtime"
        }
        return wanted.takeIf { it in keys }
    }

    private fun sortedLocalEntries(entries: List<LocalDocumentEntry>): List<LocalDocumentEntry> =
        when (mutableUi.value.librarySort) {
            LibrarySort.NAME -> entries.sortedWith(
                compareByDescending<LocalDocumentEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
            LibrarySort.DATE -> entries.sortedWith(
                compareByDescending<LocalDocumentEntry> { it.isDirectory }
                    .thenByDescending { it.lastModifiedMillis ?: 0L }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
        }

    /** Re-fetches the open server directory in the newly selected order. */
    private fun refreshServerDirectoryForSort() {
        val path = mutableUi.value.currentDirectory?.path ?: return
        if (mutableUi.value.capabilities?.hasLibrary != true || serverSortParam() == null) return
        libraryAction("Could not re-sort the library") { active ->
            val page = active.fetchLibraryPage(path, sort = serverSortParam())
            if (active !== controller || mutableUi.value.currentDirectory?.path != path) {
                return@libraryAction
            }
            mutableUi.update {
                it.copy(
                    currentDirectory = page.directory,
                    libraryRoot = if (path.isEmpty()) page.directory else it.libraryRoot,
                    libraryNextCursor = page.nextCursor,
                    selectedLibraryNode = null,
                )
            }
        }
    }

    /**
     * One library request made for a user action. This is where the wait for
     * a connect that has been running behind the list finally shows: the
     * action raises the loading overlay, lets the connect finish, and only
     * then asks the server. The flag is owned here from start to finish so a
     * controller swapped mid-request cannot leave it set.
     */
    private fun libraryAction(
        failurePrefix: String,
        request: suspend (RelaySessionController) -> Unit,
    ) {
        if (mutableUi.value.libraryLoading) return
        mutableUi.update { it.copy(libraryLoading = true, error = null) }
        viewModelScope.launch(actionErrors) {
            try {
                // A failed connect has already said why.
                var active = connectionForAction() ?: return@launch
                var result = runPlaybackCatching { request(active) }
                if (result.isFailure && active === controller) {
                    // Most often a connection that died while the tablet
                    // slept and had not been noticed. That is ours to fix,
                    // not the user's to read about: reconnect and ask again.
                    AppLog.i(TAG, "library request failed (${result.exceptionOrNull()?.message}); retrying on a fresh connection")
                    active = reconnectForAction() ?: return@launch
                    result = runPlaybackCatching { request(active) }
                }
                result.onFailure { error ->
                    if (active === controller) reportLibraryError("$failurePrefix: ${error.message}")
                }
            } finally {
                mutableUi.update { it.copy(libraryLoading = false) }
            }
        }
    }

    /**
     * The live control connection for a user action. Connects that happen on
     * their own never block the screen, so the action that actually needs the
     * server is where the wait lands: it lets a connect already in flight
     * finish, and makes one attempt of its own when there is still nothing to
     * talk to. Returns null — with the reason in `error` — when the server
     * cannot be reached.
     *
     * Never call this from closingJob or backgroundConnectJob themselves.
     */
    private suspend fun connectionForAction(): RelaySessionController? {
        closingJob?.join()
        backgroundConnectJob?.join()
        quietConnectJob?.join()
        controller?.takeIf { it.state.value == SessionState.BROWSING }?.let { return it }
        val host = mutableUi.value.host.trim()
        val port = mutableUi.value.port.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            mutableUi.update { it.copy(error = "Enter a valid host and port.") }
            return null
        }
        connectInternal(host, port, visible = false, resetPlayback = false)
        return controller?.takeIf { it.state.value == SessionState.BROWSING }
    }

    /**
     * Replaces a connection that just failed a user's request with a fresh
     * one, so the request can be made again before anything is reported.
     * Returns null — with the reason in `error` — when the server cannot be
     * reached.
     */
    private suspend fun reconnectForAction(): RelaySessionController? {
        quietConnectJob?.cancelAndJoin()
        val host = mutableUi.value.host.trim()
        val port = mutableUi.value.port.toIntOrNull() ?: return null
        connectInternal(host, port, visible = false, resetPlayback = false)
        return controller?.takeIf { it.state.value == SessionState.BROWSING }
    }

    /**
     * Brings the open directory up to date without showing anything: the
     * listing on screen stays usable and is swapped for the server's current
     * one when it arrives. Used when the app returns to the foreground with
     * its connection intact.
     */
    private fun refreshLibraryQuietly() {
        val state = mutableUi.value
        val directory = state.currentDirectory ?: return
        val active = controller?.takeIf { it.state.value == SessionState.BROWSING } ?: return
        if (state.playingPath != null || state.busy || state.libraryLoading) return
        if (state.capabilities?.hasLibrary != true) return
        if (quietRefreshJob?.isActive == true || closingJob?.isActive == true ||
            backgroundConnectJob?.isActive == true || openingJob?.isActive == true
        ) {
            return
        }
        quietRefreshJob = viewModelScope.launch(actionErrors) {
            val (fresh, cursor) = runPlaybackCatching {
                val first = active.fetchLibraryPage(directory.path, sort = serverSortParam())
                pageInChildren(active, first.directory, first.nextCursor, directory.children.size)
            }.getOrElse { error ->
                AppLog.i(TAG, "quiet library refresh failed: ${error.message}")
                // Usually a socket that died while the tablet slept and has
                // not been noticed yet; the reconnect refreshes the listing.
                if (active === controller) {
                    reconnectQuietly("library refresh failed", connectionKnownDead = true)
                }
                return@launch
            }
            mutableUi.update { current ->
                // Only if the user is still looking at the listing this
                // replaces, and nothing they started is in flight.
                if (active !== controller || current.currentDirectory !== directory ||
                    current.libraryLoading || current.busy
                ) {
                    current
                } else {
                    current.copy(
                        currentDirectory = fresh,
                        libraryRoot = if (fresh.path.isEmpty()) fresh else current.libraryRoot,
                        libraryNextCursor = cursor,
                        selectedLibraryNode = current.selectedLibraryNode?.takeIf { selected ->
                            fresh.children.any { it.path == selected.path }
                        },
                    )
                }
            }
        }
    }

    /**
     * Pages [directory] forward until it holds at least [wanted] children, so
     * a refreshed listing is as long as the one it replaces and the scroll
     * position still exists in it.
     */
    private suspend fun pageInChildren(
        active: RelaySessionController,
        directory: LibraryNode,
        cursor: String?,
        wanted: Int,
    ): Pair<LibraryNode, String?> {
        var current = directory
        var nextCursor = cursor
        var extraPages = 0
        while (nextCursor != null && current.children.size < wanted &&
            extraPages < RESTORE_MAX_EXTRA_PAGES
        ) {
            val more = active.fetchLibraryPage(current.path, nextCursor, sort = serverSortParam())
            current = current.copy(
                children = (current.children + more.directory.children).distinctBy { it.path },
            )
            nextCursor = more.nextCursor
            extraPages += 1
        }
        return current to nextCursor
    }

    fun clearRecents() {
        persist { preferences.clearRecents() }
    }

    fun dismissError() {
        mutableUi.value = mutableUi.value.copy(error = null)
    }

    fun connect() = connect(visible = true)

    /**
     * [visible] is false only for the cold-start connect behind a cached
     * listing; a connect the user asked for always shows that it is working.
     */
    private fun connect(visible: Boolean) {
        if (mutableUi.value.busy || openingJob?.isActive == true || closingJob?.isActive == true) return
        cleanupFailure = null
        pendingFailure = null
        val host = mutableUi.value.host.trim()
        val port = mutableUi.value.port.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            mutableUi.value = mutableUi.value.copy(error = "Enter a valid host and port.")
            return
        }
        if (visible) mutableUi.update { it.copy(busy = true, error = null) }
        val inFlight = backgroundConnectJob
        val quietConnect = quietConnectJob
        val job = viewModelScope.launch(actionErrors) {
            // Two connects must not interleave on one controller slot.
            quietConnect?.cancelAndJoin()
            inFlight?.join()
            connectInternal(host, port, quiet = !visible, visible = visible)
        }
        if (visible) openingJob = job else backgroundConnectJob = job
    }

    /**
     * Opens a fresh control connection.
     *
     * When the library on screen already belongs to this server it stays
     * there, usable, for the whole connect, and is replaced in one step by
     * the server's current listing of the same directory — so a reconnect
     * never empties the screen. Only a different server starts from nothing.
     *
     * [quiet] belongs to the connects the app makes on its own: a failure
     * there is not news the user needs, so nothing is reported — whatever the
     * user does next that needs the server connects again and speaks for
     * itself. [visible] raises the shell's loading overlay
     * and is for connects the user asked for. [resetPlayback] is off for
     * callers that manage the player fields themselves, because a file may
     * already be opening on top of a connect that runs in the background.
     */
    private suspend fun connectInternal(
        host: String,
        port: Int,
        quiet: Boolean = false,
        visible: Boolean = true,
        resetPlayback: Boolean = true,
    ) {
        playerReady.await()
        cleanupFailure?.let { throw it }
        val origin = "$host:$port"
        if (visible) mutableUi.update { it.copy(busy = true) }
        // OkHttp's WebSocket close path may touch the socket synchronously.
        // Retrying therefore must not dispose the previous controller on the
        // Android main thread.
        withContext(Dispatchers.IO) { disposeController() }
        awaitNetwork()
        val shown = mutableUi.value.currentDirectory
        val keepLibrary = shown != null && libraryOrigin == origin
        // The directory to re-open on the fresh connection (exiting a video
        // and reconnects both come through here). With nothing on screen
        // (first connect after an app restart) the persisted last-browsed
        // path takes its place.
        val previousDirectoryPath = shown?.path ?: persistedLibraryPath
        mutableUi.update { state ->
            // A user-initiated connect clears the banner; a quiet retry leaves
            // it, since the message on screen may be one no reconnect answers.
            var next = state.copy(error = if (quiet) state.error else null)
            if (resetPlayback) {
                next = next.copy(
                    endpoint = null,
                    session = null,
                    playingPath = null,
                    paused = false,
                    seeking = false,
                    seekPreviewSeconds = null,
                    seekTargetSeconds = null,
                )
            }
            if (!keepLibrary) {
                next = next.copy(
                    currentDirectory = null,
                    capabilities = null,
                    libraryRoot = null,
                    libraryNextCursor = null,
                    directoryCursorStack = emptyList(),
                    selectedLibraryNode = null,
                )
            }
            next
        }
        AppLog.i(TAG, "connect $host:$port display=${mutableUi.value.display.width}x${mutableUi.value.display.height} keepLibrary=$keepLibrary")
        val next = RelaySessionController(host, port, attachmentCacheRoot)
        controller = next
        collectController(next)
        val rootSort = when (mutableUi.value.librarySort) {
            LibrarySort.NAME -> "name"
            LibrarySort.DATE -> "mtime"
        }
        try {
            runPlaybackCatching { next.connect(mutableUi.value.display, rootSort) }
                .onSuccess { connected ->
                    AppLog.i(
                        TAG,
                        "server auxiliary capabilities muxed=${connected.capabilities.muxedAuxTracks} " +
                            "attachmentCache=${connected.capabilities.attachmentCacheVersion}",
                    )
                    val selectedModel = mutableUi.value.selectedModel.takeIf { selected ->
                        connected.capabilities.models.any { it.name == selected }
                    } ?: connected.capabilities.phaseOneModel
                    val androidQualities = connected.capabilities.qualityOptions
                        .filter { it.androidSupported && it.id in RelaySessionController.ANDROID_HEVC_TIERS }
                    val selectedQuality = mutableUi.value.qualityTier.takeIf { selected ->
                        androidQualities.any { it.id == selected }
                    } ?: androidQualities.firstOrNull()?.id ?: "lossless-hevc"
                    // Capabilities first: the restore below sorts by what the
                    // server advertises.
                    mutableUi.update {
                        it.copy(
                            capabilities = connected.capabilities,
                            selectedModel = selectedModel,
                            qualityTier = selectedQuality,
                        )
                    }
                    persist {
                        preferences.setModel(selectedModel)
                        preferences.setQualityTier(selectedQuality)
                    }
                    val rootView =
                        RestoredLibrary(connected.root, emptyList(), emptyList(), connected.nextCursor)
                    val restored = if (connected.capabilities.hasLibrary) {
                        restoreServerDirectory(
                            active = next,
                            root = rootView,
                            path = previousDirectoryPath,
                            // At least as long as the listing it replaces.
                            minimumChildren = if (keepLibrary) shown?.children?.size ?: 0 else 0,
                        ) ?: rootView
                    } else {
                        rootView
                    }
                    if (next === controller) {
                        libraryOrigin = origin
                        // Reachable again: the banner that said otherwise goes.
                        val stale = connectionError
                        connectionError = null
                        if (stale != null) {
                            mutableUi.update { if (it.error == stale) it.copy(error = null) else it }
                        }
                        mutableUi.update { state ->
                            // "Up" stays usable behind a background connect.
                            val view = if (keepLibrary) {
                                restored.truncatedTo(state.currentDirectory?.path ?: previousDirectoryPath)
                            } else {
                                restored
                            }
                            state.copy(
                                libraryRoot = view.stack.firstOrNull() ?: view.directory,
                                currentDirectory = view.directory,
                                directoryStack = view.stack,
                                directoryCursorStack = view.cursorStack,
                                libraryNextCursor = view.nextCursor,
                                selectedLibraryNode = state.selectedLibraryNode?.takeIf { selected ->
                                    keepLibrary && view.directory.children.any { it.path == selected.path }
                                },
                            )
                        }
                        pendingFailure = null
                    }
                }
                .onFailure { error ->
                    AppLog.i(TAG, "connect to $origin failed: ${error.message}")
                    if (!quiet) {
                        // A server that is simply not there gets a sentence,
                        // not a socket exception; a server that answered and
                        // refused keeps its own words.
                        val message = if (classifyFailure(error).recoverable) {
                            "Could not reach the server at $origin."
                        } else {
                            error.message
                        }
                        connectionError = message
                        mutableUi.update { it.copy(error = message) }
                    }
                }
        } finally {
            if (visible) mutableUi.update { it.copy(busy = false) }
        }
    }

    /**
     * Re-opens [path] on a fresh connection, walking each path segment against
     * the current library so the Up chain gets current listings. Returns null
     * when any segment fails (the layout changed on the server); the caller
     * shows the root listing instead.
     */
    private suspend fun restoreServerDirectory(
        active: RelaySessionController,
        root: RestoredLibrary,
        path: String,
        minimumChildren: Int,
    ): RestoredLibrary? = runPlaybackCatching {
        var parent = root.directory
        var parentCursor = root.nextCursor
        val stack = mutableListOf<LibraryNode>()
        val cursors = mutableListOf<String?>()
        var currentPath = ""
        if (path.isNotEmpty()) {
            for (segment in path.split('/')) {
                currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
                val page = active.fetchLibraryPage(currentPath, sort = serverSortParam())
                stack += parent
                cursors += parentCursor
                parent = page.directory
                parentCursor = page.nextCursor
            }
        }
        // Page in enough children that the remembered scroll position exists
        // again; LazyListState clamps if the list still ends up shorter than
        // before.
        val wanted = maxOf(savedListScroll("server:$path").first + 1, minimumChildren)
        val (directory, cursor) = pageInChildren(active, parent, parentCursor, wanted)
        RestoredLibrary(directory, stack.toList(), cursors.toList(), cursor)
    }.getOrElse { error ->
        AppLog.i(TAG, "library restore of '$path' fell back to root: ${error.message}")
        null
    }

    fun openDirectory(directory: LibraryNode) {
        if (directory.type != LibraryNode.Type.DIRECTORY) return
        if (mutableUi.value.currentDirectory == null) return
        libraryAction("Could not load ${directory.name}") { active ->
            val page = active.fetchLibraryPage(directory.path, sort = serverSortParam())
            if (active !== controller) return@libraryAction
            // Read here, not before the request: a connect that finished
            // first has replaced the listing this was tapped in.
            val state = mutableUi.value
            val current = state.currentDirectory ?: return@libraryAction
            mutableUi.value = state.copy(
                currentDirectory = page.directory,
                directoryStack = state.directoryStack + current,
                directoryCursorStack = state.directoryCursorStack + state.libraryNextCursor,
                libraryNextCursor = page.nextCursor,
                selectedLibraryNode = null,
            )
            persist { preferences.setLastLibraryPath(page.directory.path) }
        }
    }

    fun loadMoreLibrary() {
        if (mutableUi.value.libraryNextCursor == null) return
        val path = mutableUi.value.currentDirectory?.path ?: return
        libraryAction("Could not load more files") { active ->
            val state = mutableUi.value
            val directory = state.currentDirectory?.takeIf { it.path == path } ?: return@libraryAction
            val cursor = state.libraryNextCursor ?: return@libraryAction
            val page = active.fetchLibraryPage(directory.path, cursor, sort = serverSortParam())
            if (active !== controller || mutableUi.value.currentDirectory !== directory) {
                return@libraryAction
            }
            val merged = directory.copy(
                children = (directory.children + page.directory.children).distinctBy { it.path },
            )
            mutableUi.update {
                it.copy(
                    currentDirectory = merged,
                    libraryRoot = if (directory.path.isEmpty()) merged else it.libraryRoot,
                    libraryNextCursor = page.nextCursor,
                )
            }
        }
    }

    /**
     * A library request that failed twice — the second time on a connection
     * made for it — is a failure the user asked to hear about.
     */
    private fun reportLibraryError(message: String) {
        mutableUi.update { it.copy(error = message) }
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
            libraryNextCursor = mutableUi.value.directoryCursorStack.lastOrNull(),
            directoryCursorStack = mutableUi.value.directoryCursorStack.dropLast(1),
            selectedLibraryNode = null,
        )
        persist { preferences.setLastLibraryPath(stack.last().path) }
    }

    fun openRecent(path: String) {
        // No connection needed up front: opening a file connects on demand.
        if (path.isNotBlank()) openFile(LibraryNode(
            type = LibraryNode.Type.FILE,
            name = path.substringAfterLast('/'),
            path = path,
        ))
    }

    fun openRecentLocal(uri: String) = openLocalDocument(uri)

    fun openLocalTree(uriValue: String) {
        if (mutableUi.value.busy) return
        viewModelScope.launch(actionErrors) {
            mutableUi.value = mutableUi.value.copy(busy = true, error = null)
            runPlaybackCatching {
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
                localEntriesRaw = entries
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    localDirectoryName = name,
                    localEntries = sortedLocalEntries(entries),
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
        viewModelScope.launch(actionErrors) {
            mutableUi.value = mutableUi.value.copy(busy = true, error = null)
            runPlaybackCatching {
                withContext(Dispatchers.IO) {
                    LocalDocumentBrowser.children(getApplication(), tree, entry.uri.toUri())
                }
            }.onSuccess { entries ->
                localDirectoryStack = localDirectoryStack + (entry.uri.toUri() to entry.name)
                localEntriesRaw = entries
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    localDirectoryName = entry.name,
                    localEntries = sortedLocalEntries(entries),
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
        viewModelScope.launch(actionErrors) {
            mutableUi.value = mutableUi.value.copy(busy = true, error = null)
            runPlaybackCatching {
                withContext(Dispatchers.IO) {
                    LocalDocumentBrowser.children(getApplication(), tree, directory)
                }
            }.onSuccess { entries ->
                localDirectoryStack = nextStack
                localEntriesRaw = entries
                mutableUi.value = mutableUi.value.copy(
                    busy = false,
                    localDirectoryName = name,
                    localEntries = sortedLocalEntries(entries),
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
        openingJob = viewModelScope.launch(actionErrors) {
            mutableUi.update { it.copy(busy = true, error = null) }
            val currentController = connectionForAction()
            if (currentController == null || mutableUi.value.capabilities == null) {
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
                seekPreviewSeconds = null,
                seekTargetSeconds = null,
            )
            var bridge: LocalDocumentHttpServer? = null
            var pendingSource: AndroidMediaSource? = null
            try {
                runPlaybackCatching {
                    val landscape = withTimeoutOrNull(5_000) {
                        ui.first { it.display.width > it.display.height }
                    }
                    checkNotNull(landscape) { "Timed out waiting for the landscape player surface." }
                    val (source, localBridge) = withContext(Dispatchers.IO) {
                        val uri = uriValue.toUri()
                        val source = AndroidMediaSource.open(getApplication(), uri).also { pendingSource = it }
                        try {
                            val localBridge = LocalDocumentHttpServer(getApplication(), uri)
                            bridge = localBridge // publish ownership before IO dispatcher returns
                            source to localBridge
                        } catch (error: Throwable) {
                            source.close()
                            throw error
                        }
                    }
                    val endpoint = currentController.prepareLocalPlayback(
                        source = source,
                        originalMediaUrl = localBridge.url,
                        display = landscape.display,
                        requestedModel = landscape.selectedModel,
                        qualityTier = landscape.qualityTier,
                        fitMode = landscape.fitMode,
                        resizeAlgorithm = landscape.resizeAlgorithm.ifEmpty { null },
                    )
                    pendingSource = null // controller now owns the source
                    applyResumePoint(currentController, endpoint, "local:$uriValue") to
                        source.videoInfo.name
                }.onSuccess { (endpoint, displayName) ->
                    AppLog.i(TAG, "opened local file '$displayName' session=${endpoint.session.sessionId} model=${endpoint.model} tier=${endpoint.qualityTier} out=${endpoint.session.downlinkWidth}x${endpoint.session.downlinkHeight} epoch=${endpoint.session.epoch}")
                    withContext(Dispatchers.IO) { localDocumentServer?.close() }
                    localDocumentServer = bridge
                    bridge = null
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
                    loadRelayEndpoint(endpoint)
                    currentController.startServerPlayback()
                    startMetrics(currentController)
                }.onFailure { error ->
                    val fallbackBridge = bridge
                    withContext(Dispatchers.IO) { localDocumentServer?.close() }
                    localDocumentServer = fallbackBridge
                    bridge = null
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
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { bridge?.close() } finally { pendingSource?.close() }
                }
            }
        }
    }

    fun openFile(file: LibraryNode) {
        if (file.type != LibraryNode.Type.FILE || mutableUi.value.busy) return
        openingJob = viewModelScope.launch(actionErrors) {
            mutableUi.value = mutableUi.value.copy(
                busy = true,
                error = null,
                playingPath = file.path,
                seekPreviewSeconds = null,
                seekTargetSeconds = null,
            )
            runPlaybackCatching {
                // The player is already up with its "Preparing" overlay, so a
                // connect still running behind the library is waited out
                // there.
                var currentController = checkNotNull(connectionForAction()) {
                    mutableUi.value.error ?: "Unable to connect to the upscale server."
                }
                // Setting playingPath asks the Activity to enter sensor
                // landscape. Wait for the recreated Compose surface to report
                // its real pixels before negotiating the server output size.
                val landscape = withTimeoutOrNull(5_000) {
                    ui.first { it.display.width > it.display.height }
                }
                checkNotNull(landscape) { "Timed out waiting for the landscape player surface." }
                suspend fun prepare(active: RelaySessionController) = active.preparePlayback(
                    path = file.path,
                    display = landscape.display,
                    requestedModel = landscape.selectedModel,
                    qualityTier = landscape.qualityTier,
                    fitMode = landscape.fitMode,
                    resizeAlgorithm = landscape.resizeAlgorithm.ifEmpty { null },
                )
                openRetryPending = true
                val first = runPlaybackCatching { prepare(currentController) }
                val endpoint = first.getOrElse { error ->
                    // A control socket that died unnoticed (the tablet slept)
                    // fails the open the moment it is used. The server is
                    // still there, so reconnect and open again rather than
                    // showing a connection error over a reachable server.
                    if (currentController.failure.value?.kind?.recoverable != true) {
                        openRetryPending = false
                        throw error
                    }
                    AppLog.i(TAG, "open failed on a dead connection (${error.message}); reconnecting once")
                    // Replacing the controller retires its failure collector,
                    // so the flag can drop once that is done.
                    val fresh = reconnectForAction()
                    openRetryPending = false
                    currentController = checkNotNull(fresh) {
                        mutableUi.value.error ?: "Unable to connect to the upscale server."
                    }
                    prepare(currentController)
                }
                openRetryPending = false
                applyResumePoint(currentController, endpoint, "server:${file.path}") to
                    currentController
            }
                .onSuccess { (endpoint, currentController) ->
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
                    loadRelayEndpoint(endpoint)
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
        }.also { job -> job.invokeOnCompletion { openRetryPending = false } }
    }

    fun togglePaused() {
        val next = !mutableUi.value.paused
        runPlaybackCatching {
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
        val preference = mutableUi.value.tracks.firstOrNull {
            it.type == MpvTrack.Type.SUBTITLE && it.id == id
        }?.preferenceKey
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

    /**
     * Seeks to the next (+1) or previous (-1) chapter mark. Previous restarts
     * the current chapter when already well into it, like every player's back
     * button. A no-op when the session has no chapters.
     */
    fun chapterStep(direction: Int) {
        val chapters = mutableUi.value.session?.chapters.orEmpty()
        if (chapters.isEmpty() || direction == 0) return
        val position = mutableUi.value.mpvMetrics.positionSeconds
        val current = chapters.indexOfLast { it.startSeconds <= position }
        val target = if (direction > 0) {
            chapters.getOrNull(current + 1)?.startSeconds
        } else {
            when {
                current < 0 -> null
                position - chapters[current].startSeconds > CHAPTER_RESTART_THRESHOLD_SECONDS ->
                    chapters[current].startSeconds
                current > 0 -> chapters[current - 1].startSeconds
                else -> 0.0
            }
        }
        if (target != null) seekTo(target)
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
        if (!seconds.isFinite() || mutableUi.value.busy || closingJob?.isActive == true) return
        val prior = seekJob
        seekJob = viewModelScope.launch(actionErrors) {
            prior?.cancelAndJoin()
            if (mutableUi.value.directLocalFallback) {
                val duration = mutableUi.value.mpvMetrics.durationSeconds
                val target = seconds.coerceIn(0.0, duration.takeIf { it > 0 } ?: Double.MAX_VALUE)
                playerEngine.seekDirect(target)
                seekTargetSetAt = SystemClock.elapsedRealtime()
                mutableUi.value = mutableUi.value.copy(
                    seekPreviewSeconds = null,
                    seekTargetSeconds = target,
                )
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
            seekTargetSetAt = SystemClock.elapsedRealtime()
            mutableUi.value = mutableUi.value.copy(
                seeking = true,
                seekPreviewSeconds = null,
                seekTargetSeconds = targetSeconds,
            )
            AppLog.i(TAG, "seek to %.1fs".format(targetSeconds))
            try {
                currentController.expectPlayerReload()
                playerEngine.prepareReload()
                playerEngine.awaitIdle()
                val endpoint = currentController.seek(targetPts)
                // stop -> retire old loopback/queue -> settle -> loadfile.
                // Never pass start=: absolute Matroska PTS remain authoritative.
                delay(150)
                loadRelayEndpoint(endpoint)
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
        val closing = closingJob
        // Nothing left to close: this is a second back press on the same exit.
        if (closing?.isActive == true && mutableUi.value.playingPath == null) return
        closingJob = viewModelScope.launch(actionErrors) {
            // The library the user came from is still in memory, so it is
            // back on screen at once; tearing the session down and opening a
            // fresh control connection happen behind it.
            mutableUi.update { it.copy(endpoint = null, playingPath = null) }
            openingJob?.cancelAndJoin()
            openingJob = null
            closing?.join()
            backgroundConnectJob?.cancelAndJoin()
            val host = mutableUi.value.host.trim()
            val port = mutableUi.value.port.toIntOrNull() ?: 8590
            activeOrigin = null
            reconnectExhausted = false
            pendingFailure = null
            quietConnectJob?.cancelAndJoin()
            autoAdvanceJob?.cancelAndJoin()
            autoAdvanceJob = null
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            restartJob?.cancelAndJoin()
            restartJob = null
            seekJob?.cancelAndJoin()
            seekJob = null
            metricsJob?.cancelAndJoin()
            metricsJob = null
            // Stop listening first: stopping mpv resets the session's sockets,
            // and that is the teardown working, not a failure to recover from
            // or report over the library.
            controllerCollectors?.cancelAndJoin()
            controllerCollectors = null
            stopPlayerForDisposal()
            stopSystemMediaIntegration()
            subtitlePreferenceAppliedSession = null
            try {
                disposeController()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { localDocumentServer?.close() }
                localDocumentServer = null
                localDocumentUri = null
            }
            mutableUi.update {
                it.copy(
                    busy = false,
                    session = null,
                    paused = false,
                    seeking = false,
                    seekPreviewSeconds = null,
                    seekTargetSeconds = null,
                    localPlayback = false,
                    directLocalFallback = false,
                    reconnecting = null,
                    performanceWarning = null,
                )
            }
            connectInternal(host, port, quiet = true, visible = false, resetPlayback = false)
        }
    }

    fun playLocalFallback() {
        val bridge = localDocumentServer ?: return
        val position = mutableUi.value.mpvMetrics.positionSeconds
        AppLog.i(TAG, "direct local fallback at %.1fs".format(position))
        if (closingJob?.isActive == true) return
        closingJob = viewModelScope.launch(actionErrors) {
            openingJob?.cancelAndJoin()
            openingJob = null
            autoAdvanceJob?.cancelAndJoin()
            autoAdvanceJob = null
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            restartJob?.cancelAndJoin()
            restartJob = null
            metricsJob?.cancelAndJoin()
            metricsJob = null
            seekJob?.cancelAndJoin()
            seekJob = null
            stopPlayerForDisposal()
            val cleanupError = runPlaybackCatching { disposeController() }.exceptionOrNull()
            delay(100)
            playerEngine.loadDirect(bridge.url, position)
            mutableUi.value = mutableUi.value.copy(
                endpoint = bridge.url,
                error = null,
                busy = false,
                directLocalFallback = true,
                paused = false,
                seeking = false,
                seekPreviewSeconds = null,
                seekTargetSeconds = null,
                reconnecting = null,
                performanceWarning = cleanupError?.message,
            )
            startMetrics(null)
        }
    }

    fun retry() {
        cleanupFailure = null
        // A dropped stream is retried where it stopped; anything else goes
        // back to the library.
        if (resumePendingPlayback("retry")) return
        if (closingJob?.isActive == true) return
        closingJob = viewModelScope.launch(actionErrors) {
            openingJob?.cancelAndJoin()
            openingJob = null
            activeOrigin = null
            reconnectExhausted = false
            pendingFailure = null
            quietConnectJob?.cancelAndJoin()
            autoAdvanceJob?.cancelAndJoin()
            autoAdvanceJob = null
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            restartJob?.cancelAndJoin()
            restartJob = null
            metricsJob?.cancelAndJoin()
            metricsJob = null
            seekJob?.cancelAndJoin()
            seekJob = null
            stopPlayerForDisposal()
            stopSystemMediaIntegration()
            withContext(Dispatchers.IO) { localDocumentServer?.close() }
            localDocumentServer = null
            localDocumentUri = null
            mutableUi.update {
                it.copy(
                    busy = false,
                    endpoint = null,
                    session = null,
                    playingPath = null,
                    error = null,
                    paused = false,
                    seeking = false,
                    seekPreviewSeconds = null,
                    seekTargetSeconds = null,
                    localPlayback = false,
                    directLocalFallback = false,
                    reconnecting = null,
                    performanceWarning = null,
                )
            }
            val host = mutableUi.value.host.trim()
            val port = mutableUi.value.port.toIntOrNull() ?: 8590
            // Back to the library, which is still in memory: connect behind it.
            connectInternal(host, port, quiet = true, visible = false, resetPlayback = false)
        }
    }

    private fun collectController(value: RelaySessionController) {
        controllerCollectors?.cancel()
        controllerCollectors = viewModelScope.launch(actionErrors) {
            // Only a connection that was up can be "lost". A connect that never
            // got through must not ask for another, or the two would chase
            // each other for as long as the server is away.
            var established = false
            launch {
                value.state.collectLatest { state ->
                    AppLog.d(TAG, "controller state=$state")
                    if (state == SessionState.BROWSING) established = true
                    mutableUi.update { it.copy(sessionState = state) }
                }
            }
            launch {
                value.stats.collectLatest { stats ->
                    mutableUi.update { it.copy(transportStats = stats) }
                }
            }
            launch {
                value.openingProgress.collectLatest { progress ->
                    mutableUi.update { it.copy(openingProgress = progress) }
                }
            }
            launch {
                value.seekProgress.collectLatest { progress ->
                    mutableUi.update { it.copy(seekProgress = progress) }
                }
            }
            launch {
                value.failure.collectLatest { failure ->
                    if (failure != null) {
                        AppLog.e(TAG, "controller failure ${failure.exceptionType} (${failure.kind}): ${failure.summary}")
                        // The open in flight reconnects and tries again.
                        if (openRetryPending && failure.kind.recoverable) return@collectLatest
                        if (maybeAutoResume(failure)) return@collectLatest
                        if (mutableUi.value.playingPath == null && failure.kind.recoverable) {
                            // Losing the connection behind the library is not
                            // news. Re-make it if it had been up (the event
                            // itself proves it died; FAILED may not have
                            // reached the UI state yet) and say nothing
                            // either way: a connect the user asked for
                            // reports its own outcome.
                            if (established) {
                                reconnectQuietly("connection lost", connectionKnownDead = true)
                            }
                            mutableUi.update { it.copy(busy = false) }
                            return@collectLatest
                        }
                        mutableUi.update { it.copy(error = failureMessage(failure), busy = false) }
                    }
                }
            }
        }
    }

    private fun failureMessage(failure: FailureDetail): String =
        if (failure.summary.isBlank()) failure.kind.label
        else "${failure.kind.label} — ${failure.summary}"

    /**
     * Reconnects and resumes when relay playback loses its connection to a
     * transient network/server condition. Returns false when the failure
     * should surface as an ordinary error instead.
     */
    private fun maybeAutoResume(failure: FailureDetail): Boolean {
        if (reconnectJob?.isActive == true) return true // the reconnect owns the UI
        // A reconnect that failed stays failed: its own trailing failure
        // event must not start another. The pending failure is picked up by
        // the next thing that makes it worth trying — Retry, the app coming
        // to the foreground, a network appearing.
        if (reconnectExhausted) return pendingFailure != null
        val state = mutableUi.value
        val origin = activeOrigin ?: return false
        if (state.directLocalFallback) return false
        if (!failure.kind.recoverable || state.playingPath == null) return false
        beginAutoResume(origin, failure)
        return true
    }

    /**
     * One reconnect, made because playback needs it now. It runs under the
     * player's ordinary loading overlay; there are no counted attempts and no
     * backoff timer. The only second try is the one an event earns: the
     * network changing underneath the first (Wi-Fi returning after a wake)
     * means that attempt never had a chance.
     */
    private fun beginAutoResume(origin: PlaybackOrigin, failure: FailureDetail) {
        val position = mutableUi.value.mpvMetrics.positionSeconds
        AppLog.w(TAG, "reconnecting playback: ${failure.kind} at %.1fs".format(position))
        reconnectJob = viewModelScope.launch(actionErrors) {
            // Freeze playback so the resume position cannot drift: the local
            // audio bridge outlives the relay session and would otherwise keep
            // the audio clock running under the recovery UI.
            runPlaybackCatching { playerEngine.setPaused(true) }
            metricsJob?.cancelAndJoin()
            metricsJob = null
            seekJob?.cancelAndJoin()
            seekJob = null
            mutableUi.update {
                it.copy(error = null, busy = false, reconnecting = ReconnectStatus("Reconnecting…"))
            }
            var lastError = failure.summary
            var tries = 0
            while (true) {
                val networkBefore = networkEpoch.value
                try {
                    restartPlayback(origin, position)
                    resumeCount += 1
                    AppLog.i(TAG, "playback reconnected")
                    pendingFailure = null
                    mutableUi.update { it.copy(reconnecting = null, error = null) }
                    return@launch
                } catch (timeout: TimeoutCancellationException) {
                    // A timeout is a failed reconnect, not this job being
                    // cancelled — TimeoutCancellationException extends
                    // CancellationException.
                    lastError = timeout.message ?: lastError
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastError = error.message ?: lastError
                    if (!classifyFailure(error).recoverable) {
                        reconnectExhausted = true
                        pendingFailure = null
                        mutableUi.update { it.copy(reconnecting = null, error = lastError) }
                        return@launch
                    }
                }
                tries += 1
                if (tries >= 2 || networkEpoch.value == networkBefore) break
                AppLog.i(TAG, "network changed during the reconnect; trying on the new one")
            }
            reconnectExhausted = true
            // Kept: Retry, the next foreground, or a network appearing picks
            // it up from here.
            pendingFailure = failure
            AppLog.e(TAG, "playback reconnect failed ($lastError)")
            if (!appInForeground) {
                // Nobody is looking. Say nothing and let onStart try again
                // rather than have the user wake the tablet to an error.
                mutableUi.update { it.copy(reconnecting = null, error = null) }
                return@launch
            }
            mutableUi.update {
                it.copy(reconnecting = null, error = "${failure.kind.label} — $lastError")
            }
        }
    }

    /**
     * Re-makes a dead browse connection without showing anything, once,
     * because something just happened that makes it worth trying: the
     * connection was seen to die, the app came to the foreground, or a
     * network appeared. Nothing is scheduled behind it. If it fails, nothing
     * is said — connectionForAction connects again when the user next needs
     * the server, and that one reports. connectInternal re-opens the
     * directory that is on screen.
     *
     * Returns true when a connect is running or was started here.
     */
    private fun reconnectQuietly(trigger: String, connectionKnownDead: Boolean = false): Boolean {
        val state = mutableUi.value
        if (!state.preferencesLoaded || cleanupFailure != null) return false
        if (state.playingPath != null || state.busy || !appInForeground) return false
        // Only a server this session has already shown a library from; a
        // first connect is the user's to start.
        if (libraryOrigin == null) return false
        // On wake the UI state is the only signal; a failure event is proof
        // by itself.
        if (!connectionKnownDead &&
            state.sessionState != SessionState.FAILED &&
            state.sessionState != SessionState.DISCONNECTED
        ) {
            return false
        }
        if (quietConnectJob?.isActive == true || closingJob?.isActive == true ||
            backgroundConnectJob?.isActive == true || openingJob?.isActive == true ||
            reconnectJob?.isActive == true || restartJob?.isActive == true
        ) {
            return true
        }
        AppLog.i(TAG, "quiet reconnect ($trigger)")
        quietConnectJob = viewModelScope.launch(actionErrors) {
            val host = mutableUi.value.host.trim()
            val port = mutableUi.value.port.toIntOrNull() ?: 8590
            runPlaybackCatching { connectInternal(host, port, quiet = true, visible = false) }
                .onFailure { error -> AppLog.i(TAG, "quiet reconnect failed: ${error.message}") }
        }
        return true
    }

    /**
     * Reconnects playback for a failure that was left pending — a reconnect
     * that failed, or one that happened while the app was in the background,
     * typically a tablet that slept long enough for the control socket to
     * die. Returns true when a reconnect is already running or was started
     * here.
     */
    private fun resumePendingPlayback(trigger: String): Boolean {
        if (reconnectJob?.isActive == true || restartJob?.isActive == true) return true
        val origin = activeOrigin ?: return false
        val failure = pendingFailure ?: return false
        val state = mutableUi.value
        if (state.playingPath == null || state.directLocalFallback) return false
        pendingFailure = null
        reconnectExhausted = false
        AppLog.i(TAG, "reconnecting playback ($trigger)")
        beginAutoResume(origin, failure)
        return true
    }

    /**
     * Coming out of sleep the radio is usually still down for a moment. A
     * connect made into that fails for no reason the user would recognise, so
     * wait for the network to announce itself first. Returns at once when one
     * is already up, and gives up waiting after a bound so a tablet that is
     * genuinely offline still gets its error.
     */
    private suspend fun awaitNetwork() {
        if (connectivityManager.activeNetwork != null) return
        val snapshot = networkEpoch.value
        AppLog.i(TAG, "no network yet; waiting for one before connecting")
        withTimeoutOrNull(NETWORK_WAIT_MILLIS) { networkEpoch.first { it != snapshot } }
    }

    /**
     * Rebuilds the control connection and the active session at the given
     * position with the current UI settings. Shared by automatic resume and
     * mid-play model/quality/framing changes.
     */
    private suspend fun restartPlayback(
        origin: PlaybackOrigin,
        positionSeconds: Double,
        preserveTrackChoices: Boolean = true,
    ) {
        cleanupFailure?.let { throw it }
        awaitNetwork()
        stopPlayerForDisposal(preserveTrackChoices)
        withContext(Dispatchers.IO) { disposeController() }
        val state = mutableUi.value
        val host = state.host.trim()
        val port = state.port.toIntOrNull() ?: 8590
        val next = RelaySessionController(host, port, attachmentCacheRoot)
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
            it.copy(
                capabilities = connected.capabilities,
                libraryRoot = connected.root,
            )
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
                var pendingSource: AndroidMediaSource? = null
                var pendingBridge: LocalDocumentHttpServer? = null
                try {
                    val source = withContext(Dispatchers.IO) {
                        AndroidMediaSource.open(getApplication(), uri).also { pendingSource = it }
                    }
                    val bridge = localDocumentServer?.takeIf { localDocumentUri == origin.uriValue }
                        ?: withContext(Dispatchers.IO) {
                            LocalDocumentHttpServer(getApplication(), uri).also { pendingBridge = it }
                        }.also { fresh ->
                            withContext(Dispatchers.IO) { localDocumentServer?.close() }
                            localDocumentServer = fresh
                            localDocumentUri = origin.uriValue
                            pendingBridge = null
                        }
                    next.prepareLocalPlayback(
                        source = source,
                        originalMediaUrl = bridge.url,
                        display = state.display,
                        requestedModel = model,
                        qualityTier = tier,
                        fitMode = state.fitMode,
                        resizeAlgorithm = state.resizeAlgorithm.ifEmpty { null },
                    ).also { pendingSource = null }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { pendingBridge?.close() } finally { pendingSource?.close() }
                    }
                }
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
        val pauseIntent = mutableUi.value.paused
        mutableUi.update {
            it.copy(
                endpoint = finalEndpoint.localUrl,
                session = finalEndpoint.session,
                selectedModel = finalEndpoint.model,
                qualityTier = finalEndpoint.qualityTier,
                paused = pauseIntent,
                seeking = false,
                seekPreviewSeconds = null,
                seekTargetSeconds = null,
                busy = false,
                error = null,
                performanceWarning = null,
                sessionDescription =
                    "${finalEndpoint.model} · ${finalEndpoint.qualityTier} · " +
                        "${finalEndpoint.session.downlinkWidth}×${finalEndpoint.session.downlinkHeight}",
            )
        }
        playerEngine.setPanscan(0.0)
        loadRelayEndpoint(finalEndpoint)
        playerEngine.setPaused(pauseIntent)
        next.startServerPlayback()
        if (pauseIntent) next.setPaused(true)
        startMetrics(next)
    }

    /** Applies changed model/quality/framing settings to the active session. */
    private fun maybeRestartActiveSession() {
        val origin = activeOrigin ?: return
        val state = mutableUi.value
        if (state.playingPath == null || state.directLocalFallback) return
        if (reconnectJob?.isActive == true) return
        val prior = restartJob
        restartJob = viewModelScope.launch(actionErrors) {
            prior?.cancelAndJoin()
            seekJob?.cancelAndJoin()
            seekJob = null
            metricsJob?.cancelAndJoin()
            metricsJob = null
            val position = mutableUi.value.mpvMetrics.positionSeconds
            AppLog.i(TAG, "restarting session for changed playback settings at %.1fs".format(position))
            mutableUi.update {
                it.copy(reconnecting = ReconnectStatus("Applying playback settings"), error = null)
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

    /**
     * After a natural end-of-file, plays the next *unwatched* video file of the
     * same directory in alphabetical order — anything saved at 100% is skipped,
     * whether it was played through or marked watched by hand. Server files
     * walk the library pages; local files walk the SAF directory the video was
     * opened from.
     *
     * With the auto-play preference off the finished video leaves the player
     * instead, exactly as the back button would.
     */
    private fun maybeAutoAdvance() {
        val state = mutableUi.value
        val origin = activeOrigin ?: return
        if (state.playingPath == null || state.busy) return
        if (reconnectJob?.isActive == true || restartJob?.isActive == true) return
        if (seekJob?.isActive == true || autoAdvanceJob?.isActive == true) return
        // Only a true end-of-file advances. A downlink that dies mid-file also
        // surfaces as END_FILE, and that is the reconnect path's business.
        val duration = state.session?.durationSeconds ?: state.mpvMetrics.durationSeconds
        if (duration <= 0 ||
            state.mpvMetrics.positionSeconds < duration - AUTO_ADVANCE_END_WINDOW_SECONDS
        ) {
            return
        }
        if (!state.autoPlayNext) {
            AppLog.i(TAG, "auto-play is off; returning to the library")
            closePlayback()
            return
        }
        if (state.directLocalFallback) return // the relay server is gone; stay on this file
        autoAdvanceJob = viewModelScope.launch(actionErrors) {
            when (origin) {
                is PlaybackOrigin.ServerFile -> {
                    val active = controller ?: return@launch
                    val parent =
                        if ('/' in origin.path) origin.path.substringBeforeLast('/') else ""
                    val next = runPlaybackCatching { findNextServerFile(active, parent, origin.path) }
                        .getOrNull() ?: return@launch
                    startNextPlayback(
                        origin = PlaybackOrigin.ServerFile(next.path),
                        displayPath = next.path,
                        local = false,
                    )
                }
                is PlaybackOrigin.LocalDocument -> {
                    val next = findNextLocalFile(origin.uriValue) ?: return@launch
                    startNextPlayback(
                        origin = PlaybackOrigin.LocalDocument(next.uri),
                        displayPath = next.name,
                        local = true,
                    )
                }
            }
        }
    }

    private suspend fun findNextServerFile(
        controller: RelaySessionController,
        directory: String,
        currentPath: String,
    ): LibraryNode? {
        var cursor: String? = null
        var seenCurrent = false
        repeat(AUTO_ADVANCE_MAX_PAGES) {
            // Explicit name order regardless of the browse-sort preference:
            // "the next video" after an episode means the alphabetical next.
            val sort = "name".takeIf { it in mutableUi.value.capabilities?.librarySortKeys.orEmpty() }
            val page = controller.fetchLibraryPage(directory, cursor, sort = sort)
            for (child in page.directory.children) {
                if (child.type != LibraryNode.Type.FILE) continue
                if (seenCurrent && !isWatched("server:${child.path}")) return child
                if (child.path == currentPath) seenCurrent = true
            }
            cursor = page.nextCursor ?: return null
        }
        return null
    }

    private suspend fun findNextLocalFile(currentUri: String): LocalDocumentEntry? {
        val tree = localTreeUri ?: return null
        val directory = localDirectoryStack.lastOrNull()?.first ?: return null
        val siblings = runPlaybackCatching {
            withContext(Dispatchers.IO) {
                LocalDocumentBrowser.children(getApplication(), tree, directory)
            }
        }.getOrNull() ?: return null
        val files = siblings.filterNot { it.isDirectory }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val index = files.indexOfFirst { it.uri == currentUri }
        if (index < 0) return null
        return files.drop(index + 1).firstOrNull { !isWatched("local:${it.uri}") }
    }

    private suspend fun startNextPlayback(
        origin: PlaybackOrigin,
        displayPath: String,
        local: Boolean,
    ) {
        AppLog.i(TAG, "auto-advancing to '${displayPath.substringAfterLast('/')}'")
        activeOrigin = origin
        mutableUi.update {
            it.copy(
                playingPath = displayPath,
                localPlayback = local,
                directLocalFallback = false,
                paused = false,
                seeking = false,
                seekPreviewSeconds = null,
                seekTargetSeconds = null,
                reconnecting = ReconnectStatus("Playing next video"),
                error = null,
            )
        }
        try {
            restartPlayback(origin, 0.0, preserveTrackChoices = false)
            mutableUi.update { it.copy(reconnecting = null) }
            persist {
                when (origin) {
                    is PlaybackOrigin.ServerFile -> preferences.addRecent(origin.path)
                    is PlaybackOrigin.LocalDocument -> preferences.addRecentLocalUri(origin.uriValue)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLog.e(TAG, "auto-advance failed: ${error.message}")
            mutableUi.update {
                it.copy(
                    reconnecting = null,
                    error = "Could not play the next video: ${error.message}",
                )
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

                    override fun onFastForward() = skip(1)

                    override fun onRewind() = skip(-1)

                    override fun onSkipToNext() = chapterStep(1)

                    override fun onSkipToPrevious() = chapterStep(-1)

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
        runPlaybackCatching { PlaybackService.start(getApplication()) }
    }

    private fun stopSystemMediaIntegration() {
        PlaybackBridge.snapshot.value = PlaybackBridge.Snapshot()
        runPlaybackCatching { PlaybackService.stop(getApplication()) }
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
        if (state.playingPath != null && !state.paused) runPlaybackCatching { togglePaused() }
    }

    private fun resumeForSystem() {
        val state = mutableUi.value
        if (state.playingPath != null && state.paused) runPlaybackCatching { togglePaused() }
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
        val saved = playbackPositions[key]?.positionSeconds ?: return endpoint
        val duration = endpoint.session.durationSeconds ?: return endpoint
        val timeBase = endpoint.session.timeBase ?: return endpoint
        if (!saved.isFinite() || saved < RESUME_MIN_SECONDS || saved > duration - RESUME_END_WINDOW_SECONDS) {
            return endpoint
        }
        AppLog.i(TAG, "resuming at saved position %.1fs".format(saved))
        return controller.seek((saved / timeBase.value).roundToLong())
    }

    private fun loadRelayEndpoint(endpoint: PlaybackEndpoint) {
        val manifestBytes = endpoint.session.attachmentManifest.sumOf { it.size }
        val cache = endpoint.attachmentCacheStats
        AppLog.i(
            TAG,
            "relay auxiliary requested=${endpoint.requestedAuxTracks ?: "omitted"}/" +
                "${endpoint.requestedAuxAttachments ?: "omitted"} " +
                "confirmed=${endpoint.auxTracks}/${endpoint.auxAttachments} " +
                "manifestObjects=${endpoint.session.attachmentManifest.size} " +
                "manifestBytes=$manifestBytes cacheHits=${cache.hits} cacheMisses=${cache.misses} " +
                "verifiedBytes=${cache.verifiedBytes} evictions=${cache.evictions}",
        )
        playerEngine.load(
            RelayLoad(
                streamUrl = endpoint.localUrl,
                externalMediaUrl = endpoint.originalMediaUrl,
                subtitleFontsDirectory = endpoint.subtitleFontsDirectory,
                auxMode = when {
                    endpoint.auxTracks == "muxed" -> RelayAuxMode.MUXED
                    endpoint.session.sourceHasAuxiliary == false -> RelayAuxMode.NONE
                    endpoint.session.sourceHasAudio == false -> RelayAuxMode.EXTERNAL_SUBTITLES
                    else -> RelayAuxMode.EXTERNAL
                },
            ),
        )
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
            // Inside the credits window the file counts as fully played: pin
            // the entry at 100% rather than deleting it so history remains.
            persist { preferences.setPlaybackPosition(key, duration, duration) }
        } else {
            persist { preferences.setPlaybackPosition(key, position, duration.coerceAtLeast(0.0)) }
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
        val chapterActions = if (state.session?.chapters?.isNotEmpty() == true) {
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        } else 0L
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_STOP or chapterActions,
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
        lastDriftResyncAt = 0L
        rebufferTimestamps = emptyList()
        metricsStartedAt = SystemClock.elapsedRealtime()
        decoderDropsWindow = SystemClock.elapsedRealtime() to 0L
        metricsJob = viewModelScope.launch(actionErrors) {
            while (true) {
                val metrics = playerEngine.snapshot()
                val tracks = withContext(Dispatchers.IO) { playerEngine.trackSnapshot() }
                val sessionId = mutableUi.value.session?.sessionId
                val subtitles = tracks.filter { it.type == MpvTrack.Type.SUBTITLE }
                if (sessionId != null && sessionId != trackDiagnosticsLoggedSession && tracks.isNotEmpty()) {
                    AppLog.i(
                        TAG,
                        "mpv tracks audio=${tracks.count { it.type == MpvTrack.Type.AUDIO }} " +
                            "subtitles=${subtitles.size} external=${tracks.count { it.external }}",
                    )
                    trackDiagnosticsLoggedSession = sessionId
                }
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
                releaseSeekTargetIfCaughtUp(metrics)
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
     * Hands the seek bar back to mpv once its reported position has caught up
     * with the committed seek target (or the reload clearly went elsewhere).
     */
    private fun releaseSeekTargetIfCaughtUp(mpv: MpvMetrics) {
        val target = mutableUi.value.seekTargetSeconds ?: return
        if (mutableUi.value.seeking || seekJob?.isActive == true) return
        val caughtUp = mutableUi.value.playerState == MpvPlaybackState.PLAYING &&
            kotlin.math.abs(mpv.positionSeconds - target) < SEEK_TARGET_SNAP_SECONDS
        val expired = SystemClock.elapsedRealtime() - seekTargetSetAt > SEEK_TARGET_TIMEOUT_MILLIS
        if (caughtUp || (expired && controller?.seekProgressIdleMillis() == null)) {
            mutableUi.update { it.copy(seekTargetSeconds = null) }
        }
    }

    /**
     * Client-side stall detection plus capability/sustain warnings, evaluated
     * once per metrics tick during relay playback.
     */
    private fun handleWatchdogs(transport: TransportStats, mpv: MpvMetrics) {
        val now = SystemClock.elapsedRealtime()
        val state = mutableUi.value
        val seekIdleMillis = controller?.seekProgressIdleMillis()

        // Stalled connection: mpv is starved and the downlink byte counter has
        // not moved. A healthy watermark pause keeps mpv's cache full, so
        // requiring paused_for_cache avoids false positives.
        if (transport.receivedBytes != lastReceivedBytes) {
            lastReceivedBytes = transport.receivedBytes
            lastReceiveChangeAt = now
        } else if (
            now - lastReceiveChangeAt > STALL_TIMEOUT_MILLIS &&
            state.sessionState == SessionState.PLAYING &&
            (seekIdleMillis == null || seekIdleMillis > 60_000) &&
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

        // A/V gap: MediaCodec cannot decode without its output Surface, so
        // anything that takes it away — Picture-in-Picture, backgrounding, a
        // Surface teardown — lets video run ahead of the audio clock while the
        // player is away. Measured at 24.9 s after twenty seconds in PiP. mpv
        // cannot close that by seeking, because the relay stream is a live
        // one-shot socket, so it converges by running a track off speed for
        // about as long as the gap itself: tens of seconds of stutter and
        // dropped frames. A fresh epoch at the audio position — what the user
        // actually heard, so nothing is skipped — costs about a second.
        //
        // This lives here rather than on a lifecycle callback because PiP
        // never stops the Activity, so onStart/onStop do not see it at all.
        //
        // The measure is mpv's own A/V synchronisation error, not the distance
        // between the position and audio readouts: a user-set audio delay
        // separates those two permanently, and comparing them directly would
        // read a standing delay as a fault and reload the epoch on a loop
        // forever. mpv applies the delay and reports the residual error, which
        // stays microscopic whether or not one is set.
        //
        // Session warm-up (empty cache before the first rendered frame, cold
        // pipeline right after a restart) is excluded, here and by the
        // rebuffer warning below.
        val steadyState = state.sessionState == SessionState.PLAYING &&
            now - metricsStartedAt > 15_000
        if (
            steadyState && !state.paused && !state.directLocalFallback &&
            state.reconnecting == null && reconnectJob?.isActive != true &&
            restartJob?.isActive != true && seekJob?.isActive != true &&
            mpv.audioPtsSeconds > 0.0 && mpv.positionSeconds > 0.0 &&
            now - lastDriftResyncAt > DRIFT_RESYNC_COOLDOWN_MILLIS
        ) {
            val error = kotlin.math.abs(mpv.avSyncSeconds)
            if (error >= DRIFT_RESYNC_MIN_SECONDS) {
                lastDriftResyncAt = now
                AppLog.i(TAG, "resyncing playback: %.1fs A/V error".format(error))
                seekTo(mpv.audioPtsSeconds)
            }
        }

        // Server-sustain warning: repeated real rebuffers in a short window.
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
                put("auto_resume_count", resumeCount)
                put("auto_play_next_enabled", state.autoPlayNext)
                put("reconnecting", state.reconnecting != null)
                put("performance_warning", state.performanceWarning ?: "")
                put("audio_track_count", state.tracks.count { it.type == MpvTrack.Type.AUDIO })
                put("subtitle_track_count", state.tracks.count { it.type == MpvTrack.Type.SUBTITLE })
                put("external_track_count", state.tracks.count { it.external })
            }
            File(getApplication<Application>().filesDir, "phase4-latest.json").writeText(report.toString())
        }

    private suspend fun stopPlayerForDisposal(preserveTrackChoices: Boolean = false) {
        try {
            if (preserveTrackChoices) playerEngine.prepareReload() else playerEngine.stop()
            playerEngine.awaitIdle()
        } catch (error: Throwable) {
            // A native timeout must not orphan the remote session. Retire all
            // controller owners, but never continue into a replacement load.
            try {
                disposeController()
            } catch (cleanupError: Throwable) {
                if (cleanupError !== error) cleanupError.addSuppressed(error)
                throw cleanupError
            }
            throw error
        }
    }

    private suspend fun disposeController() = withContext(NonCancellable + Dispatchers.IO) {
        disposalMutex.withLock {
            // A cancelled collector can still publish one last value until its
            // cancellation is observed.  Wait for it before installing a new
            // controller so an old FAILED state cannot overwrite a successful
            // retry's BROWSING state.
            controllerCollectors?.cancelAndJoin()
            controllerCollectors = null
            val current = controller
            controller = null
            if (current != null) {
                try {
                    current.teardown()
                } catch (error: Throwable) {
                    cleanupFailure = error
                    throw error
                } finally {
                    current.close()
                }
            }
            mutableUi.update { it.copy(seekProgress = null, openingProgress = null) }
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
            runPlaybackCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        // Native/network owners may take seconds to stop. Keep the main
        // thread responsive while cleanup outlives the cancelled ViewModel scope.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                closingJob?.cancelAndJoin()
                openingJob?.cancelAndJoin()
                quietConnectJob?.cancelAndJoin()
                autoAdvanceJob?.cancelAndJoin()
                reconnectJob?.cancelAndJoin()
                restartJob?.cancelAndJoin()
                metricsJob?.cancelAndJoin()
                seekJob?.cancelAndJoin()
            } catch (error: Throwable) {
                AppLog.w(TAG, "final action retirement failed: ${error.message}")
            } finally {
                runPlaybackCatching {
                    playerEngine.stop()
                    playerEngine.awaitIdle()
                }.onFailure { AppLog.w(TAG, "final player stop failed: ${it.message}") }
                runPlaybackCatching { disposeController() }
                    .onFailure { AppLog.w(TAG, "final session cleanup failed: ${it.message}") }
                try {
                    localDocumentServer?.close()
                } finally {
                    playerEngine.close()
                }
            }
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "RelayAndroid"
        private const val STALL_TIMEOUT_MILLIS = 15_000L
        private const val PROGRESS_SAVE_INTERVAL_MILLIS = 5_000L
        private const val RESUME_MIN_SECONDS = 10.0
        private const val RESUME_END_WINDOW_SECONDS = 90.0
        private const val CHAPTER_RESTART_THRESHOLD_SECONDS = 3.0
        private const val SEEK_TARGET_SNAP_SECONDS = 8.0

        /**
         * Below this an A/V gap costs less to let mpv absorb than a reload
         * does. A rotation or a glance at the shade never reaches it.
         */
        private const val DRIFT_RESYNC_MIN_SECONDS = 2.0

        /** Keeps a gap that survives one reload from reloading on a loop. */
        private const val DRIFT_RESYNC_COOLDOWN_MILLIS = 15_000L
        private const val SEEK_TARGET_TIMEOUT_MILLIS = 15_000L
        private const val AUTO_ADVANCE_END_WINDOW_SECONDS = 60.0
        private const val AUTO_ADVANCE_MAX_PAGES = 20
        private const val RESTORE_MAX_EXTRA_PAGES = 10
        private const val NETWORK_WAIT_MILLIS = 10_000L
        private const val LIBRARY_CACHE_WRITE_DELAY_MILLIS = 500L
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}

enum class TabletDestination { SERVER, LOCAL, RECENT, SETTINGS }

/** File-picker ordering: alphabetical, or newest first by modification time. */
enum class LibrarySort { NAME, DATE }

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
    val directoryCursorStack: List<String?> = emptyList(),
    val libraryNextCursor: String? = null,
    val libraryLoading: Boolean = false,
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
    // The last committed seek target. Keeps the seek bar at the user's chosen
    // position while the relay session restarts, instead of mirroring mpv's
    // transient 0:00 during the reload.
    val seekTargetSeconds: Double? = null,
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
    val librarySort: LibrarySort = LibrarySort.NAME,
    val recentPaths: List<String> = emptyList(),
    val recentLocalUris: List<String> = emptyList(),
    val recentLocalRootUris: List<String> = emptyList(),
    // Saved watch state keyed like progressKey ("server:<path>"/"local:<uri>"),
    // for the percentage + last-played labels in the file lists.
    val playbackProgress: Map<String, PlaybackProgress> = emptyMap(),
    val playbackHistoryLimit: Int = MAX_POSITIONS,
    val skipSeconds: Int = DEFAULT_SKIP_SECONDS,
    val localDirectoryName: String? = null,
    val localEntries: List<LocalDocumentEntry> = emptyList(),
    val localCanGoUp: Boolean = false,
    val localPlayback: Boolean = false,
    val directLocalFallback: Boolean = false,
    val autoPlayNext: Boolean = true,
    val reconnecting: ReconnectStatus? = null,
    // Server loading text while open_session runs (TensorRT engine build).
    val openingProgress: String? = null,
    val seekProgress: SeekProgress? = null,
    val performanceWarning: String? = null,
    val backupStatus: BackupStatus? = null,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val displayResampleSync: Boolean = false,
    val interpolationEnabled: Boolean = false,
    val interpolationScaler: String = "oversample",
    val backgroundPlayback: Boolean = true,
    val fileLoggingEnabled: Boolean = false,
    val logFileName: String? = null,
)

/** What the player is re-establishing its session for, shown while it does. */
data class ReconnectStatus(val reason: String)

/** Outcome of the last backup export/import, shown in the Settings card. */
data class BackupStatus(val message: String, val failed: Boolean = false)

private val MpvTrack.preferenceKey: String get() = "$language\u001f$title"

/** Coroutine cancellation retires an action; it is never a UI failure callback. */
internal inline fun <T> runPlaybackCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (timeout: TimeoutCancellationException) {
    Result.failure(timeout)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
