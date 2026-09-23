package org.upscalerelay.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.player.mpv.MpvTrack
import org.upscalerelay.protocol.LibraryNode
import java.io.File

/** Runs only in the co-installed debug app; never changes the user's release data. */
@RunWith(AndroidJUnit4::class)
class AuditPlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun closeAndImmediatelyReopenNativePlayer() {
        requireDebugPackage()
        repeat(3) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = model(scenario)
                await("player initialization", model) { it.preferencesLoaded }
                assertEquals(MpvPlaybackState.IDLE, model.playerEngine.state.value)
            }
        }
    }

    @Test
    fun localRelayCanSeekAndSwitchToOriginal() {
        requireDebugPackage()
        val arguments = InstrumentationRegistry.getArguments()
        val filename = arguments.getString("auditLocalFile")
        assumeTrue("pass -e auditLocalFile <fixture in app files>", !filename.isNullOrBlank())
        require(filename == File(filename!!).name)
        val file = File(instrumentation.targetContext.filesDir, filename)
        check(file.isFile)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            await("preferences", model) { it.preferencesLoaded }
            onMain {
                model.setHost(arguments.getString("auditHost") ?: "192.168.0.115")
                model.setAutoResume(false)
                model.setAutoPlayNext(false)
                model.setModel("passthrough")
                model.setQualityTier("hevc-qp18")
                model.openLocalDocument(Uri.fromFile(file).toString())
            }
            await("local relay", model, 90_000) {
                it.playerState == MpvPlaybackState.PLAYING && it.mpvMetrics.positionSeconds > 1 && !it.busy
            }
            onMain { model.seekTo(5.0) }
            await("local seek", model) {
                (it.session?.epoch ?: 0) > 0 && !it.seeking && it.mpvMetrics.positionSeconds >= 5
            }
            onMain { model.playLocalFallback() }
            await("direct original readiness", model) {
                it.directLocalFallback && it.playerState == MpvPlaybackState.PLAYING &&
                    it.mpvMetrics.positionSeconds > 5
            }
            onMain { model.togglePaused() }
            assertTrue("fallback pause control must work immediately", model.ui.value.paused)
            onMain { model.seekTo(2.0) }
            await("direct original seek", model) { kotlin.math.abs(it.mpvMetrics.positionSeconds - 2) < 1 }
            capture("audit-local-fallback.png")
            onMain { model.closePlayback() }
            await("close local source", model, 60_000) {
                it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
            }
        }
    }

    @Test
    fun pausedSeekAndSettingsRestartRetainIntent() {
        requireDebugPackage()
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("auditMediaPath")
        assumeTrue("pass -e auditMediaPath <server-library file>", !path.isNullOrBlank())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            await("preferences", model) { it.preferencesLoaded }
            onMain {
                model.setHost(arguments.getString("auditHost") ?: "192.168.0.115")
                model.setPort(arguments.getString("auditPort") ?: "8590")
                model.setAutoConnect(false)
                model.setAutoResume(false)
                model.setAutoPlayNext(false)
                model.setModel("passthrough")
                model.setQualityTier("hevc-qp18")
                model.connect()
            }
            await("connect", model) { it.sessionState == SessionState.BROWSING && !it.busy }
            onMain {
                model.openFile(LibraryNode(LibraryNode.Type.FILE, path!!.substringAfterLast('/'), path))
            }
            await("initial playback", model, 90_000) {
                it.playerState == MpvPlaybackState.PLAYING && it.mpvMetrics.positionSeconds > 1.0 && !it.busy
            }
            val initial = model.ui.value
            assertTrue("hardware decode should be active", initial.mpvMetrics.hardwareDecoder.contains("mediacodec"))
            capture("audit-playback.png")
            val target = minOf(20.0, (initial.session?.durationSeconds ?: 120.0) / 3)
            onMain {
                model.selectSubtitleTrack(null)
                model.togglePaused()
                model.seekTo(target)
            }
            await("paused seek", model) {
                (it.session?.epoch ?: 0) > (initial.session?.epoch ?: 0) && !it.seeking &&
                    it.playerState == MpvPlaybackState.PLAYING &&
                    kotlin.math.abs(it.mpvMetrics.positionSeconds - target) < 8
            }
            assertTrue(model.ui.value.paused)
            assertTrue(model.ui.value.tracks.none { it.type == MpvTrack.Type.SUBTITLE && it.selected })
            capture("audit-paused-seek.png")
            val sessionId = model.ui.value.session?.sessionId
            onMain { model.setFitMode(if (model.ui.value.fitMode == "fit") "cover" else "fit") }
            await("paused settings restart", model, 90_000) {
                it.session?.sessionId != sessionId && it.reconnecting == null &&
                    it.playerState == MpvPlaybackState.PLAYING && it.mpvMetrics.positionSeconds > 0.0
            }
            assertTrue("settings restart must remain paused", model.ui.value.paused)
            assertTrue("subtitles off must survive restart",
                model.ui.value.tracks.none { it.type == MpvTrack.Type.SUBTITLE && it.selected })
            val pausedPosition = model.ui.value.mpvMetrics.positionSeconds
            SystemClock.sleep(1_500)
            assertEquals(pausedPosition, model.ui.value.mpvMetrics.positionSeconds, 0.3)
            val priorEpoch = model.ui.value.session!!.epoch
            for (offset in listOf(3.0, 8.0, 1.0)) {
                onMain { model.seekTo(target + offset) }
                SystemClock.sleep(100)
            }
            await("overlapping paused seeks", model) {
                (it.session?.epoch ?: 0) > priorEpoch && !it.seeking &&
                    it.playerState == MpvPlaybackState.PLAYING &&
                    kotlin.math.abs(it.mpvMetrics.positionSeconds - target - 1.0) < 8
            }
            assertTrue(model.ui.value.paused)
            onMain { model.togglePaused() }
            await("resume", model) { !it.paused && it.mpvMetrics.positionSeconds > target + 2 }
            onMain { model.closePlayback() }
            await("acknowledged close and return to library", model, 60_000) {
                it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
            }
            capture("audit-library.png")
        }
    }

    private fun requireDebugPackage() {
        check(instrumentation.targetContext.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the audit tests"
        }
    }

    private fun model(scenario: ActivityScenario<MainActivity>): RelayViewModel {
        lateinit var model: RelayViewModel
        scenario.onActivity { model = ViewModelProvider(it)[RelayViewModel::class.java] }
        return model
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(
        label: String,
        model: RelayViewModel,
        timeoutMillis: Long = 45_000,
        ready: (RelayUiState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.ui.value
            check(state.error == null) { "$label: ${state.error}" }
            if (ready(state)) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label; state=${model.ui.value.sessionState}, player=${model.ui.value.playerState}")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
