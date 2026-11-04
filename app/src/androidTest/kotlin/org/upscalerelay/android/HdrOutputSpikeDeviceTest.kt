package org.upscalerelay.android

import android.app.PictureInPictureParams
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import java.io.File

/**
 * HDR output spike (prototype branch only). Plays one server file under the
 * mpv options given in `-e spikeConf "a=b;c=d"` (written to
 * files/mpv-spike.conf before the player starts) and records startup, seek
 * latency, drops and what SurfaceFlinger shows.
 *
 * `-e e2eHost -e e2ePort -e spikePath -e spikeLabel [-e spikeHold 20]
 *  [-e spikeSeeks 600,120] [-e spikeTier hevc-qp4] [-e spikeFit cover]
 *  [-e spikeSid no|auto|<id>]`
 */
@RunWith(AndroidJUnit4::class)
class HdrOutputSpikeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val preferences by lazy { AppPreferencesStore(context) }

    @Test
    fun playAndMeasure() {
        val host = arguments.getString("e2eHost")
        val port = arguments.getString("e2ePort")
        val path = arguments.getString("spikePath")
        assumeTrue("pass -e e2eHost -e e2ePort -e spikePath", !host.isNullOrBlank() && !port.isNullOrBlank() && !path.isNullOrBlank())
        val label = arguments.getString("spikeLabel") ?: "run"
        val hold = arguments.getString("spikeHold")?.toLong() ?: 20L
        val seeks = arguments.getString("spikeSeeks")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }.orEmpty()
        val conf = arguments.getString("spikeConf").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        File(context.filesDir, "mpv-spike.conf").writeText(conf.joinToString("\n", postfix = "\n"))
        val out = StringBuilder()
        fun record(line: String) {
            Log.i(TAG, "[$label] $line")
            out.append(line).append('\n')
        }
        record("conf=$conf path=$path")

        val original = runBlocking { preferences.snapshot() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: RelayViewModel
                scenario.onActivity {
                    // The tablet is locked: show over the keyguard, test only.
                    it.setShowWhenLocked(true)
                    it.setTurnScreenOn(true)
                    model = ViewModelProvider(it)[RelayViewModel::class.java]
                }
                await("preferences", model) { it.preferencesLoaded }
                onMain {
                    model.setHost(host!!)
                    model.setPort(port!!)
                    model.setAutoPlayNext(false)
                    model.setModel("passthrough")
                    model.setQualityTier(arguments.getString("spikeTier") ?: "hevc-qp4")
                    model.setFitMode(arguments.getString("spikeFit") ?: "cover")
                    if (arguments.getString("spikeBackground") == "true") model.setBackgroundPlayback(true)
                    model.connect()
                }
                await("connect", model, 30_000) {
                    it.sessionState == SessionState.BROWSING && !it.busy && it.capabilities != null && it.port == port
                }

                val opened = SystemClock.elapsedRealtime()
                onMain { model.openRecent(path!!) }
                val startup = awaitPlaying(model, opened, 0.0, 120_000)
                record("startup_ms=$startup")
                arguments.getString("spikeSid")?.let { sid ->
                    onMain { model.selectSubtitleTrack(if (sid == "no") null else sid.toIntOrNull()) }
                }
                SystemClock.sleep(3_000)
                snapshot("start", model, ::record)
                surfaceState(label, "steady", ::record)
                val before = model.ui.value.mpvMetrics
                SystemClock.sleep(hold * 1000)
                val after = model.ui.value.mpvMetrics
                record(
                    "steady_${hold}s output_drops=${after.outputDroppedFrames - before.outputDroppedFrames} " +
                        "decoder_drops=${after.decoderDroppedFrames - before.decoderDroppedFrames} " +
                        "avsync=${after.avSyncSeconds} cache_ms=${after.cacheDurationMillis}",
                )
                surfaceState(label, "steady-end", ::record)

                for (target in seeks) {
                    val m0 = model.ui.value.mpvMetrics
                    val started = SystemClock.elapsedRealtime()
                    onMain { model.seekTo(target) }
                    val latency = awaitPlaying(model, started, target, 60_000)
                    SystemClock.sleep(8_000)
                    val m1 = model.ui.value.mpvMetrics
                    record(
                        "seek_to=$target latency_ms=$latency output_drops_8s=${m1.outputDroppedFrames - m0.outputDroppedFrames} " +
                            "decoder_drops=${m1.decoderDroppedFrames - m0.decoderDroppedFrames} avsync=${m1.avSyncSeconds}",
                    )
                }
                snapshot("end", model, ::record)
                if (arguments.getString("spikeBackground") == "true") {
                    val m0 = model.ui.value.mpvMetrics
                    scenario.moveToState(Lifecycle.State.CREATED)
                    SystemClock.sleep(15_000)
                    val away = model.ui.value
                    record(
                        "background_15s state=${away.playerState} error=${away.error} pos=${away.mpvMetrics.positionSeconds} " +
                            "audio_pts=${away.mpvMetrics.audioPtsSeconds} avsync=${away.mpvMetrics.avSyncSeconds} " +
                            "hwdec=${away.mpvMetrics.hardwareDecoder}",
                    )
                    val back = SystemClock.elapsedRealtime()
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    var resumedAfter = -1L
                    var last = -1.0
                    while (SystemClock.elapsedRealtime() - back < 30_000) {
                        val state = model.ui.value
                        val position = state.mpvMetrics.positionSeconds
                        if (state.playerState == MpvPlaybackState.PLAYING && last >= 0 && position > last) {
                            resumedAfter = SystemClock.elapsedRealtime() - back
                            break
                        }
                        last = position
                        SystemClock.sleep(50)
                    }
                    SystemClock.sleep(8_000)
                    val m1 = model.ui.value.mpvMetrics
                    record(
                        "returned picture_moving_after_ms=$resumedAfter state=${model.ui.value.playerState} error=${model.ui.value.error} " +
                            "output_drops=${m1.outputDroppedFrames - m0.outputDroppedFrames} decoder_drops=${m1.decoderDroppedFrames - m0.decoderDroppedFrames} " +
                            "avsync=${m1.avSyncSeconds} hwdec=${m1.hardwareDecoder} pos=${m1.positionSeconds}",
                    )
                    surfaceState(label, "returned", ::record)
                }
                if (arguments.getString("spikePip") == "true") {
                    val m0 = model.ui.value.mpvMetrics
                    scenario.onActivity { it.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
                    SystemClock.sleep(20_000)
                    val m1 = model.ui.value.mpvMetrics
                    record(
                        "pip_20s output_drops=${m1.outputDroppedFrames - m0.outputDroppedFrames} " +
                            "decoder_drops=${m1.decoderDroppedFrames - m0.decoderDroppedFrames} avsync=${m1.avSyncSeconds} " +
                            "pos=${m1.positionSeconds} audio_pts=${m1.audioPtsSeconds} state=${model.ui.value.playerState}",
                    )
                    surfaceState(label, "pip", ::record)
                }
                onMain { model.closePlayback() }
                await("close", model, 60_000) { it.playingPath == null && !it.busy }
            }
        } finally {
            File(context.filesDir, "hdr-spike-$label.txt").writeText(out.toString())
            File(context.filesDir, "mpv-spike.conf").delete()
            runBlocking {
                preferences.setHost(original.host)
                preferences.setPort(original.port)
                preferences.setAutoPlayNext(original.autoPlayNext)
                preferences.setModel(original.model)
                preferences.setQualityTier(original.qualityTier)
                preferences.setFitMode(original.fitMode)
                preferences.setBackgroundPlayback(original.backgroundPlayback)
            }
        }
    }

    /** Milliseconds until the picture moves at or past [target]. */
    private fun awaitPlaying(model: RelayViewModel, started: Long, target: Double, timeout: Long): Long {
        var lastPosition = -1.0
        val deadline = started + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.ui.value
            check(state.error == null) { "error: ${state.error}" }
            val position = state.mpvMetrics.positionSeconds
            if (state.playerState == MpvPlaybackState.PLAYING && !state.busy && position >= target - 1.0) {
                if (lastPosition >= 0 && position > lastPosition) return SystemClock.elapsedRealtime() - started
                lastPosition = position
            }
            SystemClock.sleep(20)
        }
        error("timed out waiting for playback at $target")
    }

    private fun snapshot(tag: String, model: RelayViewModel, record: (String) -> Unit) {
        val state = model.ui.value
        model.playerEngine.trackSnapshot().forEach { track ->
            record("$tag track ${track.type} id=${track.id} codec=${track.codec} lang=${track.language} selected=${track.selected} external=${track.external}")
        }
        val m = state.mpvMetrics
        record(
            "$tag hwdec=${m.hardwareDecoder} pixfmt=${m.videoPixelFormat}/${m.videoHwPixelFormat} gamma=${m.videoGamma} " +
                "prim=${m.videoPrimaries} sigpeak=${m.videoSignalPeak} pos=${m.positionSeconds} avsync=${m.avSyncSeconds} " +
                "drops=${m.outputDroppedFrames}/${m.decoderDroppedFrames} session=${state.session}",
        )
    }

    private fun surfaceState(label: String, tag: String, record: (String) -> Unit) {
        val flinger = shell("dumpsys SurfaceFlinger")
        File(context.filesDir, "hdr-spike-$label-$tag-sf.txt").writeText(flinger)
        val lines = flinger.lines()
        lines.forEachIndexed { index, line ->
            if ("SurfaceView[org.upscalerelay" in line && "(BLAST)" in line && " | " in line) {
                record("sf[$tag] ${line.trim().take(160)}")
                lines.getOrNull(index + 1)?.let { record("sf[$tag]   ${it.trim().take(200)}") }
            }
            if ("HDR events" in line || "hdrOutputType" in line && "activeMode" in line) record("sf[$tag] ${line.trim().take(200)}")
        }
        shell("dumpsys display").lines()
            .filter { "mIsHdrLayerPresent" in it || "mHdrConversionMode" in it || "HdrBrightness" in it && "mHdrBrightnessData" !in it }
            .take(8)
            .forEach { record("display[$tag] ${it.trim()}") }
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(label: String, model: RelayViewModel, timeoutMillis: Long = 20_000, ready: (RelayUiState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready(model.ui.value)) return
            SystemClock.sleep(100)
        }
        val state = model.ui.value
        error("Timed out: $label; session=${state.sessionState} player=${state.playerState} error=${state.error}")
    }

    private companion object {
        const val TAG = "HdrSpike"
    }
}
