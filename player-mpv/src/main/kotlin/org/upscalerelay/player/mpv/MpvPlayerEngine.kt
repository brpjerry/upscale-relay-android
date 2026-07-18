package org.upscalerelay.player.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun initialize(): Unit = synchronized(lock) {
        check(!closed) { "player is closed" }
        if (initialized) return
        MPVLib.create(applicationContext)
        setInitialOptions()
        MPVLib.init()
        observeMetrics()
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        initialized = true
        mutableState.value = MpvPlaybackState.IDLE
    }

    fun load(url: String, originalMediaUrl: String) = synchronized(lock) {
        check(initialized && !closed)
        require(url.startsWith("tcp://127.0.0.1:")) { "mpv input must be the private loopback stream" }
        require(originalMediaUrl.startsWith("http://") || originalMediaUrl.startsWith("https://")) {
            "original media must be served over HTTP"
        }
        val request = MpvLoadRequest(url, originalMediaUrl, null)
        mutableState.value = MpvPlaybackState.LOADING
        if (attachedSurface == null) pendingLoad = request else loadNow(request)
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
            MPVLib.command(arrayOf("stop"))
            mutableState.value = MpvPlaybackState.LOADING
        }
    }

    fun setPaused(paused: Boolean) = synchronized(lock) {
        if (initialized && !closed) MPVLib.setPropertyBoolean("pause", paused)
    }

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
        if (initialized && !closed) MPVLib.setPropertyString("aid", id.toString())
    }

    fun selectSubtitleTrack(id: Int?) = synchronized(lock) {
        if (initialized && !closed) MPVLib.setPropertyString("sid", id?.toString() ?: "no")
    }

    fun trackSnapshot(): List<MpvTrack> = synchronized(lock) {
        if (!initialized || closed) return emptyList()
        val count = MPVLib.getPropertyInt("track-list/count") ?: return emptyList()
        (0 until count).mapNotNull { index ->
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
                selected = MPVLib.getPropertyBoolean("$prefix/selected") == true,
                external = MPVLib.getPropertyBoolean("$prefix/external") == true,
            )
        }
    }

    fun stop(): Unit = synchronized(lock) {
        if (!initialized || closed) return
        pendingLoad = null
        reloading = false
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
        val options = if (request.originalMediaUrl != null) {
            // Relay playback never passes start=. Absolute Matroska PTS remain authoritative.
            val source = fixedLengthOptionValue(request.originalMediaUrl)
            // sub-files is a colon-separated path list on Android. Its -append
            // variant accepts exactly one unescaped item, avoiding URL splitting.
            "audio-file=$source,sub-files-append=$source"
        } else {
            "start=${request.startSeconds ?: 0.0}"
        }
        MPVLib.command(arrayOf("loadfile", request.localUrl, "replace", "-1", options))
        reloading = false
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
        synchronized(lock) {
            if (eventId == MPVLib.MpvEvent.END_FILE && reloading) return@synchronized
            mutableState.value = when (eventId) {
                MPVLib.MpvEvent.FILE_LOADED -> MpvPlaybackState.LOADED
                MPVLib.MpvEvent.PLAYBACK_RESTART -> MpvPlaybackState.PLAYING
                MPVLib.MpvEvent.END_FILE -> MpvPlaybackState.ENDED
                MPVLib.MpvEvent.SHUTDOWN -> MpvPlaybackState.CLOSED
                else -> return@synchronized
            }
        }
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
        private val URL_PATTERN = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")

        /** mpv tscale filters offered for motion interpolation. */
        val INTERPOLATION_SCALERS = listOf("oversample", "linear", "catmull_rom", "mitchell")

        internal fun redactUrls(text: String): String = URL_PATTERN.replace(text, "<url>")
    }
}

private data class MpvLoadRequest(
    val localUrl: String,
    val originalMediaUrl: String?,
    val startSeconds: Double?,
)

/** mpv's fixed-length option syntax keeps commas and equals signs in URLs literal. */
internal fun fixedLengthOptionValue(value: String): String = "%${value.length}%$value"

data class MpvTrack(
    val id: Int,
    val type: Type,
    val language: String,
    val title: String,
    val selected: Boolean,
    val external: Boolean,
) {
    enum class Type { AUDIO, SUBTITLE }

    val label: String
        get() = listOf(language, title).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "Track $id" }
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
