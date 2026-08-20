package org.upscalerelay.player.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

class MpvPlayerEngine(context: Context) : MPVLib.EventObserver, MPVLib.LogObserver, AutoCloseable {
    private val applicationContext = context.applicationContext

    /** Optional mirror for forwarded (already URL-redacted) mpv log lines. */
    @Volatile
    var logSink: ((level: Int, line: String) -> Unit)? = null
    private val lock = Any()
    private val mutableState = MutableStateFlow(MpvPlaybackState.CREATED)
    val state: StateFlow<MpvPlaybackState> = mutableState.asStateFlow()
    private var initialized = false
    private var closed = false
    private var attachedSurface: Surface? = null
    private var pendingLoad: MpvLoadRequest? = null
    private var reloading = false
    private var metrics = MpvMetrics()
    /** Original-media URL still waiting to be attached to the running epoch. */
    private var pendingExternalMedia: String? = null
    /** Relay branch still waiting for its first PLAYBACK_RESTART. */
    private var pendingRelayMode: RelayAuxMode? = null
    /** True from the attach being dispatched until its commands have run. */
    private var attachInFlight = false
    /** Bumped by every load/stop so a late attach cannot target a retired one. */
    private var loadGeneration = 0L
    /** The caller's pause intent, applied once the attach releases the hold. */
    private var callerPaused = false
    // Track choices the caller made for the current file. Re-applied whenever
    // the original media is re-attached, so a seek does not silently revert to
    // whichever track mpv would pick on its own.
    private var chosenAudio: RememberedTrack? = null
    private var audioChoiceMade = false
    private var chosenSubtitle: RememberedTrack? = null
    private var subtitleChoiceMade = false
    private var defaultSubtitleFontsDirectory = ""

    fun initialize(): Unit = synchronized(lock) {
        check(!closed) { "player is closed" }
        if (initialized) return
        MPVLib.create(applicationContext)
        setInitialOptions()
        MPVLib.init()
        defaultSubtitleFontsDirectory = MPVLib.getPropertyString("sub-fonts-dir").orEmpty()
        observeMetrics()
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        initialized = true
        mutableState.value = MpvPlaybackState.IDLE
    }

    fun load(request: RelayLoad) = synchronized(lock) {
        check(initialized && !closed)
        require(request.streamUrl.startsWith("tcp://127.0.0.1:")) {
            "mpv input must be the private loopback stream"
        }
        when (request.auxMode) {
            RelayAuxMode.EXTERNAL -> require(
                request.externalMediaUrl?.startsWith("http://") == true ||
                    request.externalMediaUrl?.startsWith("https://") == true,
            ) { "external relay media must be served over HTTP" }
            RelayAuxMode.MUXED -> require(request.externalMediaUrl == null) {
                "a muxed relay load must not attach external media"
            }
        }
        val load = MpvLoadRequest(request.streamUrl, request, null)
        mutableState.value = MpvPlaybackState.LOADING
        if (attachedSurface == null) pendingLoad = load else loadNow(load)
    }

    fun loadDirect(url: String, startSeconds: Double = 0.0) = synchronized(lock) {
        check(initialized && !closed)
        require(url.startsWith("http://127.0.0.1:")) { "direct local input must use the private HTTP bridge" }
        val request = MpvLoadRequest(url, null, startSeconds.coerceAtLeast(0.0))
        mutableState.value = MpvPlaybackState.LOADING
        if (attachedSurface == null) pendingLoad = request else loadNow(request)
    }

    /** Stop the old live Matroska demuxer before its localhost socket closes. */
    fun prepareReload() {
        synchronized(lock) {
            check(initialized && !closed)
            reloading = true
            pendingLoad = null
            pendingExternalMedia = null
            pendingRelayMode = null
            attachInFlight = false
            loadGeneration += 1
            resetStreamMetricsLocked()
            MPVLib.command(arrayOf("stop"))
            mutableState.value = MpvPlaybackState.LOADING
        }
    }

    fun setPaused(paused: Boolean) = synchronized(lock) {
        callerPaused = paused
        // While an epoch is still waiting for its audio, mpv is held paused on
        // purpose; the attach applies this intent when it releases the hold.
        if (initialized && !closed && !holdingForExternalMediaLocked()) {
            MPVLib.setPropertyBoolean("pause", paused)
        }
    }

    private fun holdingForExternalMediaLocked(): Boolean =
        pendingRelayMode != null || attachInFlight

    fun seekDirect(seconds: Double) = synchronized(lock) {
        if (initialized && !closed) MPVLib.setPropertyDouble("time-pos", seconds.coerceAtLeast(0.0))
    }

    fun setPanscan(value: Double) = synchronized(lock) {
        if (initialized && !closed) MPVLib.setPropertyDouble("panscan", value.coerceIn(0.0, 1.0))
    }

    fun setDeband(enabled: Boolean) = synchronized(lock) {
        if (initialized && !closed) MPVLib.setPropertyString("deband", if (enabled) "yes" else "no")
    }

    /**
     * User playback preferences equivalent to mpv.conf's video-sync /
     * interpolation / tscale. These never touch relay-owned plumbing
     * (vo, rebase-start-time, hwdec, per-epoch reload behavior).
     */
    fun setVideoSyncPreferences(displayResample: Boolean, interpolation: Boolean, scaler: String) =
        synchronized(lock) {
            if (!initialized || closed) return
            MPVLib.setPropertyString("video-sync", if (displayResample) "display-resample" else "audio")
            // Interpolation is only meaningful under a display-* sync mode.
            MPVLib.setPropertyString(
                "interpolation",
                if (displayResample && interpolation) "yes" else "no",
            )
            if (scaler in INTERPOLATION_SCALERS) MPVLib.setPropertyString("tscale", scaler)
        }

    fun setAudioDelay(seconds: Double) = synchronized(lock) {
        if (initialized && !closed) MPVLib.setPropertyDouble("audio-delay", seconds)
    }

    fun setSubtitleDelay(seconds: Double) = synchronized(lock) {
        if (initialized && !closed) MPVLib.setPropertyDouble("sub-delay", seconds)
    }

    fun selectAudioTrack(id: Int) = synchronized(lock) {
        chosenAudio = rememberTrack(trackSnapshotLocked(), id)
        audioChoiceMade = true
        if (initialized && !closed) MPVLib.setPropertyString("aid", id.toString())
    }

    fun selectSubtitleTrack(id: Int?) = synchronized(lock) {
        chosenSubtitle = id?.let { rememberTrack(trackSnapshotLocked(), it) }
        subtitleChoiceMade = true
        if (initialized && !closed) MPVLib.setPropertyString("sid", id?.toString() ?: "no")
    }

    fun trackSnapshot(): List<MpvTrack> = synchronized(lock) { trackSnapshotLocked() }

    private fun trackSnapshotLocked(): List<MpvTrack> {
        if (!initialized || closed) return emptyList()
        val count = MPVLib.getPropertyInt("track-list/count") ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val prefix = "track-list/$index"
            val type = when (MPVLib.getPropertyString("$prefix/type")) {
                "audio" -> MpvTrack.Type.AUDIO
                "sub" -> MpvTrack.Type.SUBTITLE
                else -> return@mapNotNull null
            }
            val id = MPVLib.getPropertyInt("$prefix/id") ?: return@mapNotNull null
            MpvTrack(
                id = id,
                type = type,
                language = MPVLib.getPropertyString("$prefix/lang").orEmpty(),
                title = MPVLib.getPropertyString("$prefix/title").orEmpty(),
                codec = MPVLib.getPropertyString("$prefix/codec").orEmpty(),
                selected = MPVLib.getPropertyBoolean("$prefix/selected") == true,
                external = MPVLib.getPropertyBoolean("$prefix/external") == true,
            )
        }
    }

    fun stop(): Unit = synchronized(lock) {
        if (!initialized || closed) return
        pendingLoad = null
        pendingExternalMedia = null
        pendingRelayMode = null
        attachInFlight = false
        loadGeneration += 1
        reloading = false
        callerPaused = false
        chosenAudio = null
        audioChoiceMade = false
        chosenSubtitle = null
        subtitleChoiceMade = false
        MPVLib.setPropertyString("sub-fonts-dir", defaultSubtitleFontsDirectory)
        MPVLib.setPropertyBoolean("pause", false)
        MPVLib.command(arrayOf("stop"))
        metrics = metrics.copy(paused = false)
        mutableState.value = MpvPlaybackState.IDLE
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) = synchronized(lock) {
        check(initialized && !closed)
        if (attachedSurface === surface) {
            resize(width, height)
            return@synchronized
        }
        if (attachedSurface != null) detachSurfaceLocked(attachedSurface)
        MPVLib.attachSurface(surface)
        attachedSurface = surface
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        MPVLib.setPropertyString("force-window", "yes")
        MPVLib.setPropertyString("vo", VIDEO_OUTPUT)
        pendingLoad?.let {
            pendingLoad = null
            loadNow(it)
        }
    }

    fun resize(width: Int, height: Int) = synchronized(lock) {
        if (initialized && !closed && attachedSurface != null && width > 0 && height > 0) {
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    fun detachSurface(surface: Surface) = synchronized(lock) {
        // A destroyed old view must never detach a newer Surface.
        if (initialized && !closed && attachedSurface === surface) detachSurfaceLocked(surface)
    }

    fun snapshot(): MpvMetrics = synchronized(lock) { metrics }

    fun versionInfo(): Map<String, String> = synchronized(lock) {
        if (!initialized || closed) return emptyMap()
        mapOf(
            "mpv" to (MPVLib.getPropertyString("mpv-version") ?: "unknown"),
            "ffmpeg" to (MPVLib.getPropertyString("ffmpeg-version") ?: "unknown"),
        )
    }

    private fun setInitialOptions() {
        val options = mapOf(
            "config" to "no",
            "profile" to "fast",
            "vo" to VIDEO_OUTPUT,
            "gpu-context" to "android",
            "opengl-es" to "yes",
            "hwdec" to "mediacodec,mediacodec-copy",
            // The relay downlink is always HEVC, but direct local fallback
            // plays the original file's codec — keep the common hardware
            // MediaCodec families eligible or fallback drops to software.
            "hwdec-codecs" to "h264,hevc,vp8,vp9,av1",
            "audio" to "auto",
            "aid" to "auto",
            "sid" to "auto",
            "rebase-start-time" to "no",
            "video-sync" to "audio",
            // The relay already applies backpressure and MediaCodec reports
            // decoder losses separately. Preserve late-but-decoded frames
            // during Android Surface warmup instead of dropping them at VO.
            "osc" to "no",
            "input-default-bindings" to "no",
            "force-window" to "no",
            "idle" to "yes",
            "cache" to "yes",
            "cache-pause" to "yes",
            "demuxer-readahead-secs" to "20",
            "demuxer-max-bytes" to (128L * 1024 * 1024).toString(),
            "demuxer-max-back-bytes" to "0",
            "network-timeout" to "10",
            "vd-lavc-dr" to "yes",
            "deband" to "no",
            "msg-level" to "all=warn,vd=info,vo=info",
        )
        options.forEach { (name, value) ->
            check(MPVLib.setOptionString(name, value) >= 0) { "libmpv rejected --$name=$value" }
        }
    }

    private fun loadNow(request: MpvLoadRequest) {
        val options = if (request.relayLoad != null) {
            relayLoadOptions()
        } else {
            "start=${request.startSeconds ?: 0.0}"
        }
        resetStreamMetricsLocked()
        loadGeneration += 1
        pendingRelayMode = request.relayLoad?.auxMode
        pendingExternalMedia = request.relayLoad?.externalMediaUrl
        attachInFlight = false
        MPVLib.setPropertyString(
            "sub-fonts-dir",
            request.relayLoad?.subtitleFontsDirectory ?: defaultSubtitleFontsDirectory,
        )
        // A reload is the same file at a new epoch, so its track choices carry
        // over. Anything else is a different file whose ids mean nothing here.
        if (!reloading) {
            chosenAudio = null
            audioChoiceMade = false
            chosenSubtitle = null
            subtitleChoiceMade = false
        }
        MPVLib.command(arrayOf("loadfile", request.localUrl, "replace", "-1", options))
        reloading = false
    }

    /**
     * Adds the original file as mpv's external audio/subtitle source once the
     * epoch is actually playing.
     *
     * It cannot be passed to `loadfile`: mpv positions an external demuxer at
     * the current playback time when the track is selected, and during load
     * that time is still zero. The relay stream carries only the tail of the
     * file starting at the seek target, and it is a live one-shot socket that
     * mpv cannot seek ("Cannot seek in this stream"), so the `--start` seek
     * which would otherwise reposition the external demuxers is rejected. The
     * external tracks were therefore left parked at the beginning of the
     * original file and mpv reached the epoch by decoding everything before it
     * — tens of seconds of black screen after a far seek, scaling with the
     * seek target, while the epoch's own first frame was already decoded.
     *
     * Attaching after playback starts makes the same selection happen at a
     * known-good position, so each demuxer issues one HTTP range seek instead.
     */
    private fun attachExternalMedia(generation: Long, mediaUrl: String) {
        thread(name = "relay-mpv-external-media", isDaemon = true) {
            val audio: RememberedTrack?
            val audioChosen: Boolean
            val subtitle: RememberedTrack?
            val subtitleChosen: Boolean
            synchronized(lock) {
                if (closed || !initialized || loadGeneration != generation) {
                    // Nothing to release: whoever retired this load owns pause.
                    attachInFlight = false
                    return@thread
                }
                audio = chosenAudio
                audioChosen = audioChoiceMade
                subtitle = chosenSubtitle
                subtitleChosen = subtitleChoiceMade
            }
            try {
                // One add, not two. mpv exposes *every* track of an external
                // file, so `audio-add` already contributes this file's
                // subtitle track — a second `sub-add` only opened a duplicate
                // HTTP demuxer that re-parsed and re-seeked the same file
                // (5+ seconds of it on a busy link), and left the track lists
                // showing every audio and subtitle entry twice.
                //
                // mpv opens the URL synchronously here, so this must not run
                // on the event-callback thread. Adding mid-playback needs
                // "select": "auto" only marks the file as a candidate and
                // leaves the track unselected.
                MPVLib.command(arrayOf("audio-add", mediaUrl, if (!audioChosen) "select" else "auto"))
                val tracks = trackSnapshot()
                if (audioChosen) {
                    remapTrack(tracks, audio)?.let { MPVLib.setPropertyString("aid", it.toString()) }
                }
                selectAttachedSubtitle(tracks, subtitle, subtitleChosen)
                awaitAudioReady()
            } finally {
                // Release the load-time pause hold even if a command failed;
                // leaving it set would strand playback on the first frame.
                synchronized(lock) {
                    attachInFlight = false
                    if (initialized && !closed && loadGeneration == generation) {
                        MPVLib.setPropertyBoolean("pause", callerPaused)
                    }
                }
            }
        }
    }

    /**
     * Picks the subtitle track the attached file just contributed. The caller's
     * explicit choice wins — including "off" — and otherwise the file's first
     * subtitle track is selected, because mpv only auto-selects subtitles when
     * a file is loaded, not when tracks appear on an already-loaded one.
     */
    private fun selectAttachedSubtitle(
        tracks: List<MpvTrack>,
        subtitle: RememberedTrack?,
        subtitleChosen: Boolean,
    ) {
        if (subtitleChosen) {
            MPVLib.setPropertyString("sid", remapTrack(tracks, subtitle)?.toString() ?: "no")
            return
        }
        val first = tracks.firstOrNull { it.type == MpvTrack.Type.SUBTITLE } ?: return
        MPVLib.setPropertyString("sid", first.id.toString())
    }

    /** Reapply explicit muxed choices only after the fresh epoch exposes its track list. */
    private fun completeMuxedRestart(generation: Long) = synchronized(lock) {
        if (closed || !initialized || generation != loadGeneration) return@synchronized
        val tracks = trackSnapshotLocked()
        if (audioChoiceMade) {
            remapTrack(tracks, chosenAudio)?.let { MPVLib.setPropertyString("aid", it.toString()) }
        }
        if (subtitleChoiceMade) {
            MPVLib.setPropertyString(
                "sid",
                remapTrack(tracks, chosenSubtitle)?.toString() ?: "no",
            )
        }
        MPVLib.setPropertyBoolean("pause", callerPaused)
    }

    /**
     * Blocks until mpv has audio decoded at the epoch's position.
     *
     * `audio-add` returning only means the file was opened; mpv still has to
     * seek that demuxer and decode. Releasing the pause hold on the command's
     * return let the picture run for the couple of seconds that took, which is
     * exactly the drift the hold exists to prevent — and the audio output,
     * primed but starved, then underran and dropped frames restarting.
     * `audio-pts` becoming valid is mpv's own signal that audio has caught up.
     */
    private fun awaitAudioReady() {
        val deadline = System.nanoTime() + AUDIO_READY_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            synchronized(lock) { if (closed || !initialized) return }
            if (MPVLib.getPropertyDouble("audio-pts") != null) return
            Thread.sleep(AUDIO_READY_POLL_MILLIS)
        }
        Log.w(TAG, "audio not ready before the hold timed out; releasing anyway")
    }

    /**
     * Clears the metrics that describe the stream being retired. mpv stops
     * emitting demuxer-cache-duration while the next file loads, so without
     * this the diagnostics keep reporting the previous epoch's cache — a
     * seek that has buffered nothing still reads as several seconds full,
     * and the buffer report hands the server that same stale number.
     *
     * Only stream-scoped progress is reset. Track/codec identity, cumulative
     * drop counters, and user-owned delays survive the reload.
     */
    private fun resetStreamMetricsLocked() {
        metrics = metrics.copy(
            bitrateBitsPerSecond = 0,
            cacheDurationMillis = 0,
            cacheBufferingPercent = 0,
            pausedForCache = false,
            positionSeconds = 0.0,
            avSyncSeconds = 0.0,
            audioPtsSeconds = 0.0,
        )
    }

    private fun observeMetrics() {
        val stringProperties = listOf("hwdec-current", "video-codec", "audio-codec")
        val integerProperties = listOf(
            "video-params/w",
            "video-params/h",
            "frame-drop-count",
            "decoder-frame-drop-count",
            "cache-buffering-state",
        )
        val doubleProperties = listOf(
            "estimated-vf-fps",
            "demuxer-cache-duration",
            "video-bitrate",
            "time-pos",
            "duration",
            "avsync",
            "audio-delay",
            "sub-delay",
            "audio-pts",
        )
        stringProperties.forEach { MPVLib.observeProperty(it, MPVLib.MpvFormat.STRING) }
        integerProperties.forEach { MPVLib.observeProperty(it, MPVLib.MpvFormat.INT64) }
        doubleProperties.forEach { MPVLib.observeProperty(it, MPVLib.MpvFormat.DOUBLE) }
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("seeking", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("core-idle", MPVLib.MpvFormat.FLAG)
    }

    private fun detachSurfaceLocked(surface: Surface?) {
        if (surface == null || attachedSurface !== surface) return
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
        attachedSurface = null
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) = synchronized(lock) {
        metrics = when (property) {
            "video-params/w" -> metrics.copy(codedWidth = value.toInt())
            "video-params/h" -> metrics.copy(codedHeight = value.toInt())
            "frame-drop-count" -> metrics.copy(outputDroppedFrames = value)
            "decoder-frame-drop-count" -> metrics.copy(decoderDroppedFrames = value)
            "cache-buffering-state" -> metrics.copy(cacheBufferingPercent = value.toInt())
            else -> metrics
        }
    }

    override fun eventProperty(property: String, value: Boolean) = synchronized(lock) {
        metrics = when (property) {
            "paused-for-cache" -> metrics.copy(pausedForCache = value)
            "pause" -> metrics.copy(paused = value)
            "seeking" -> metrics.copy(seeking = value)
            "core-idle" -> metrics.copy(coreIdle = value)
            else -> metrics
        }
    }

    override fun eventProperty(property: String, value: String) = synchronized(lock) {
        metrics = when (property) {
            "hwdec-current" -> metrics.copy(hardwareDecoder = value)
            "video-codec" -> metrics.copy(codec = value)
            "audio-codec" -> metrics.copy(audioCodec = value)
            else -> metrics
        }
    }

    override fun eventProperty(property: String, value: Double) = synchronized(lock) {
        metrics = when (property) {
            "estimated-vf-fps" -> metrics.copy(framesPerSecond = value)
            "demuxer-cache-duration" -> metrics.copy(cacheDurationMillis = (value * 1000).toLong())
            "video-bitrate" -> metrics.copy(bitrateBitsPerSecond = value.toLong())
            "time-pos" -> metrics.copy(positionSeconds = value)
            "duration" -> metrics.copy(durationSeconds = value)
            "avsync" -> metrics.copy(avSyncSeconds = value)
            "audio-delay" -> metrics.copy(audioDelaySeconds = value)
            "sub-delay" -> metrics.copy(subtitleDelaySeconds = value)
            "audio-pts" -> metrics.copy(audioPtsSeconds = value)
            else -> metrics
        }
    }

    override fun event(eventId: Int) {
        var attach: Pair<Long, String>? = null
        var releaseMuxedGeneration: Long? = null
        synchronized(lock) {
            if (eventId == MPVLib.MpvEvent.END_FILE && reloading) return@synchronized
            if (eventId == MPVLib.MpvEvent.PLAYBACK_RESTART) {
                // Only the first restart owns auxiliary setup; later restarts
                // (underruns and track switches) cannot duplicate or release it.
                when (pendingRelayMode) {
                    RelayAuxMode.EXTERNAL -> {
                        pendingExternalMedia?.let {
                            attach = loadGeneration to it
                            attachInFlight = true
                        }
                    }
                    RelayAuxMode.MUXED -> releaseMuxedGeneration = loadGeneration
                    null -> Unit
                }
                pendingRelayMode = null
                pendingExternalMedia = null
            }
            mutableState.value = when (eventId) {
                MPVLib.MpvEvent.FILE_LOADED -> MpvPlaybackState.LOADED
                MPVLib.MpvEvent.PLAYBACK_RESTART -> MpvPlaybackState.PLAYING
                MPVLib.MpvEvent.END_FILE -> MpvPlaybackState.ENDED
                MPVLib.MpvEvent.SHUTDOWN -> MpvPlaybackState.CLOSED
                else -> return@synchronized
            }
        }
        attach?.let { (generation, url) -> attachExternalMedia(generation, url) }
        releaseMuxedGeneration?.let(::completeMuxedRestart)
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        // Media URLs embed the original file path; keep them out of logcat.
        val line = "[$prefix] ${redactUrls(text.trimEnd())}"
        when {
            level <= 20 -> Log.e(TAG, line)
            level <= 30 -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
        logSink?.invoke(level, line)
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        if (initialized) {
            detachSurfaceLocked(attachedSurface)
            MPVLib.removeLogObserver(this)
            MPVLib.removeObserver(this)
            MPVLib.destroy()
            initialized = false
        }
        mutableState.value = MpvPlaybackState.CLOSED
    }

    companion object {
        private const val TAG = "RelayMpv"
        private const val VIDEO_OUTPUT = "gpu"

        /** Cap on the pause hold, so a silent file can never strand playback. */
        private const val AUDIO_READY_TIMEOUT_NANOS = 5_000_000_000L
        private const val AUDIO_READY_POLL_MILLIS = 20L
        private val URL_PATTERN = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")

        /** mpv tscale filters offered for motion interpolation. */
        val INTERPOLATION_SCALERS = listOf("oversample", "linear", "catmull_rom", "mitchell")

        internal fun redactUrls(text: String): String = URL_PATTERN.replace(text, "<url>")
    }
}

private data class MpvLoadRequest(
    val localUrl: String,
    val relayLoad: RelayLoad?,
    val startSeconds: Double?,
)

enum class RelayAuxMode { EXTERNAL, MUXED }

data class RelayLoad(
    val streamUrl: String,
    val externalMediaUrl: String?,
    val subtitleFontsDirectory: String?,
    val auxMode: RelayAuxMode,
)

/**
 * Options for the live relay input. A user pause also pauses the server, so
 * the private loopback TCP stream can legitimately be silent indefinitely.
 * The ordinary ten-second network timeout would make FFmpeg abandon that
 * video stream during a longer pause; external HTTP audio would then resume
 * alone while newly relayed video backed up before mpv. Relay transport
 * liveness is owned by the control/downlink sockets and playback watchdog.
 *
 * The original media is deliberately absent: see [MpvPlayerEngine]'s
 * attachExternalMedia for why audio and subtitles are added after playback
 * starts rather than through `audio-file` / `sub-files-append` here.
 *
 * The epoch starts paused for the same reason. mpv shows the first frame and
 * reports playback-restart either way, but without the hold the picture runs
 * on alone for the second the attach takes, and mpv then has to reconcile a
 * second of drift against a freshly started audio track — an audible A/V
 * desynchronisation warning and tens of dropped frames on every stream start.
 * attachExternalMedia lifts the pause once the tracks are in place.
 */
internal fun relayLoadOptions(): String = "network-timeout=0,pause=yes"

data class MpvTrack(
    val id: Int,
    val type: Type,
    val language: String,
    val title: String,
    val codec: String,
    val selected: Boolean,
    val external: Boolean,
) {
    enum class Type { AUDIO, SUBTITLE }

    val label: String
        get() = listOf(language, title).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "Track $id" }
}

internal data class TrackDescriptor(
    val type: MpvTrack.Type,
    val language: String,
    val title: String,
    val codec: String,
    val occurrence: Int,
)

internal data class RememberedTrack(val priorId: Int, val descriptor: TrackDescriptor)

internal fun rememberTrack(tracks: List<MpvTrack>, id: Int): RememberedTrack? {
    val occurrences = mutableMapOf<List<Any>, Int>()
    tracks.forEach { track ->
        val key = listOf(track.type, track.language, track.title, track.codec)
        val occurrence = occurrences.getOrDefault(key, 0)
        occurrences[key] = occurrence + 1
        if (track.id == id) {
            return RememberedTrack(
                priorId = id,
                descriptor = TrackDescriptor(
                    track.type,
                    track.language,
                    track.title,
                    track.codec,
                    occurrence,
                ),
            )
        }
    }
    return null
}

internal fun remapTrack(tracks: List<MpvTrack>, remembered: RememberedTrack?): Int? {
    remembered ?: return null
    val descriptors = tracks.mapNotNull { track ->
        rememberTrack(tracks, track.id)?.let { track.id to it.descriptor }
    }
    return descriptors.firstOrNull { (id, descriptor) ->
        id == remembered.priorId && descriptor == remembered.descriptor
    }?.first ?: descriptors.firstOrNull { (_, descriptor) ->
        descriptor == remembered.descriptor
    }?.first
}

enum class MpvPlaybackState { CREATED, IDLE, LOADING, LOADED, PLAYING, ENDED, CLOSED }

data class MpvMetrics(
    val hardwareDecoder: String = "",
    val codec: String = "",
    val audioCodec: String = "",
    val codedWidth: Int = 0,
    val codedHeight: Int = 0,
    val framesPerSecond: Double = 0.0,
    val bitrateBitsPerSecond: Long = 0,
    val cacheDurationMillis: Long = 0,
    val outputDroppedFrames: Long = 0,
    val decoderDroppedFrames: Long = 0,
    val pausedForCache: Boolean = false,
    val paused: Boolean = false,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val avSyncSeconds: Double = 0.0,
    val audioDelaySeconds: Double = 0.0,
    val subtitleDelaySeconds: Double = 0.0,
    val audioPtsSeconds: Double = 0.0,
    val cacheBufferingPercent: Int = 0,
    val seeking: Boolean = false,
    val coreIdle: Boolean = true,
)
