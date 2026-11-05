package org.upscalerelay.android

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.PASSTHROUGH_MODEL
import org.upscalerelay.client.RelaySessionController
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.protocol.LibraryNode

/**
 * A playback setting changed while an open, or a file advance, is under way.
 * The open has already sent the old value, so the change must follow it as
 * one restart once it has published its session, and a model chosen in that
 * time must not be put back by the open's write-back. During the advance the
 * file being left's session is never shown as the next file's.
 *
 * Each change is made once the controller reports OPENING, which it does
 * only after the open has read its settings, so the race is the real one
 * every run. Needs the real relay: `-e auditMediaPath <library file>` with a
 * next file in its folder, `-e model <an upscaling model the server has>`
 * when not 2x_AnimeJaNai_HD_V3_Compact, and `-e auditHost`/`-e auditPort`
 * when it is not the default. Runs on passthrough except for the model
 * change, which plays the upscaling model for a few seconds.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRestartDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val preferences by lazy { AppPreferencesStore(context) }

    @Test
    fun aQualityChosenWhileAFileOpensRestartsItOnceItPlays() = withPlayer(autoPlayNext = false) { model, path, tiers ->
        val (openTier, chosenTier) = tiers
        onMain { model.setQualityTier(openTier) }
        val opened = openAndChange(model, path) { model.setQualityTier(chosenTier) }
        val sessions = awaitRestart(model, opened, "the restart for the quality chosen while the file opened")
        assertTrue(model.ui.value.sessionDescription, model.ui.value.sessionDescription.contains(" · $chosenTier · "))
        assertEquals(chosenTier, model.ui.value.qualityTier)
        assertEquals("one restart after the open: $sessions", 2, sessions.size)
        close(model)
    }

    @Test
    fun aModelChosenWhileAFileOpensSurvivesItsPublish() = withPlayer(autoPlayNext = false) { model, path, _ ->
        val chosen = arguments.getString("model") ?: "2x_AnimeJaNai_HD_V3_Compact"
        check(chosen != PASSTHROUGH_MODEL) { "-e model must name an upscaling model, not $chosen" }
        onMain { model.setModel(PASSTHROUGH_MODEL) }
        val opened = openAndChange(model, path) { model.setModel(chosen) }
        // The open published passthrough, which it read; the choice made
        // since stays the selection and the saved model all the while.
        val sessions = awaitRestart(model, opened, "the restart for the model chosen while the file opened") {
            check(it.selectedModel == chosen) { "the open put back ${it.selectedModel} over $chosen" }
        }
        assertEquals(chosen, model.ui.value.relaySession?.model)
        assertEquals(chosen, runBlocking { preferences.snapshot() }.model)
        assertEquals("one restart after the open: $sessions", 2, sessions.size)
        close(model)
    }

    @Test
    fun aQualityChosenDuringAnAdvanceRestartsTheNextFile() = withPlayer(autoPlayNext = true) { model, path, tiers ->
        val (openTier, chosenTier) = tiers
        onMain { model.setQualityTier(openTier) }
        open(model, path)
        val left = checkNotNull(model.ui.value.session)
        val duration = checkNotNull(left.durationSeconds) { "the server reported no duration" }
        onMain { model.seekTo(duration - END_LEAD_SECONDS) }
        // The file ends, and auto-play opens the next one.
        await("the next file to start opening", model, 90_000) {
            check(it.error == null) { it.error.toString() }
            it.playingPath != path && it.sessionState == SessionState.OPENING
        }
        val next = checkNotNull(model.ui.value.playingPath)
        onMain { model.setQualityTier(chosenTier) }
        val seen = linkedSetOf<String>()
        await("the next file to play with the quality chosen during the advance", model, 120_000) {
            check(it.error == null) { it.error.toString() }
            check(it.reconnecting?.reason != RECONNECTING) { "a reconnect ran during the advance" }
            check(it.playingPath == next) { "playing ${it.playingPath}, expected $next" }
            // The file being left is never described as the next one.
            check(it.session?.sessionId != left.sessionId) { "the session of $path was shown for $next" }
            it.session?.sessionId?.let(seen::add)
            it.playerState == MpvPlaybackState.PLAYING && it.reconnecting == null &&
                it.sessionDescription.contains(" · $chosenTier · ") && it.mpvMetrics.positionSeconds > 0.5
        }
        // The advance's session, opened with the old quality, then the restart's.
        assertEquals("one restart after the advance: $seen", 2, seen.size)
        close(model)
    }

    /**
     * Opens [path], and makes [change] once the open has sent its settings.
     * Returns the session the open published.
     */
    private fun openAndChange(model: RelayViewModel, path: String, change: () -> Unit): String {
        onMain { model.openFile(LibraryNode(LibraryNode.Type.FILE, path.substringAfterLast('/'), path)) }
        await("the open to reach the server", model, 30_000, pollMillis = 2) {
            check(it.error == null) { it.error.toString() }
            check(it.session == null) { "the open published before the change could be made" }
            it.sessionState == SessionState.OPENING
        }
        onMain(change)
        await("the open to publish", model, 120_000) {
            check(it.error == null) { it.error.toString() }
            it.session != null
        }
        return checkNotNull(model.ui.value.session?.sessionId)
    }

    /** Waits for a session after [previous] to play, and returns every session seen from [previous] on. */
    private fun awaitRestart(
        model: RelayViewModel,
        previous: String?,
        label: String,
        each: (RelayUiState) -> Unit = {},
    ): Set<String> {
        val seen = linkedSetOf<String>()
        previous?.let(seen::add)
        await(label, model, 120_000) {
            check(it.error == null) { it.error.toString() }
            check(it.reconnecting?.reason != RECONNECTING) { "a reconnect ran during the restart" }
            each(it)
            it.session?.sessionId?.let(seen::add)
            it.session?.sessionId != previous && it.playerState == MpvPlaybackState.PLAYING &&
                it.reconnecting == null && !it.busy && it.mpvMetrics.positionSeconds > 0.5
        }
        // Nothing follows once it plays: one restart, not one per change.
        SystemClock.sleep(SETTLE_MILLIS)
        model.ui.value.session?.sessionId?.let(seen::add)
        assertEquals(null, model.ui.value.reconnecting)
        return seen
    }

    private fun withPlayer(
        autoPlayNext: Boolean,
        body: (RelayViewModel, String, Pair<String, String>) -> Unit,
    ) {
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the device tests"
        }
        val path = arguments.getString("auditMediaPath")
        assumeTrue("pass -e auditMediaPath <server-library file>", !path.isNullOrBlank())
        launchOverKeyguard().use { scenario ->
            lateinit var model: RelayViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RelayViewModel::class.java] }
            await("preferences", model) { it.preferencesLoaded }
            onMain {
                model.setHost(arguments.getString("auditHost") ?: "192.168.0.115")
                model.setPort(arguments.getString("auditPort") ?: "8590")
                model.setAutoPlayNext(autoPlayNext)
                model.setModel(PASSTHROUGH_MODEL)
                model.connect()
            }
            await("connect", model, 30_000) { it.sessionState == SessionState.BROWSING && !it.busy }
            // Two tiers the server offers Android, set after the connect,
            // which replaces a tier it does not offer.
            val tiers = model.ui.value.capabilities?.qualityOptions.orEmpty()
                .filter { it.androidSupported && it.id in RelaySessionController.ANDROID_HEVC_TIERS }
                .map { it.id }
            check(tiers.size >= 2) { "the server offers Android fewer than two quality tiers: $tiers" }
            val (chosen, opened) = tiers.takeLast(2)
            assertNotEquals(opened, chosen)
            body(model, path!!, opened to chosen)
        }
    }

    private fun open(model: RelayViewModel, path: String) {
        onMain { model.openFile(LibraryNode(LibraryNode.Type.FILE, path.substringAfterLast('/'), path)) }
        await("playback of $path", model, 120_000) {
            check(it.error == null) { it.error.toString() }
            it.playerState == MpvPlaybackState.PLAYING && !it.busy && it.mpvMetrics.positionSeconds > 1.0
        }
    }

    private fun close(model: RelayViewModel) {
        onMain { model.closePlayback() }
        await("close", model, 60_000) {
            it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(
        label: String,
        model: RelayViewModel,
        timeoutMillis: Long = 20_000,
        pollMillis: Long = 20,
        ready: (RelayUiState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready(model.ui.value)) return
            SystemClock.sleep(pollMillis)
        }
        val state = model.ui.value
        error(
            "Timed out: $label; session=${state.sessionState} player=${state.playerState} " +
                "path=${state.playingPath} model=${state.relaySession?.model} " +
                "description=${state.sessionDescription} reconnecting=${state.reconnecting} error=${state.error}",
        )
    }

    private companion object {
        /** How far before the end the advance test seeks: past auto-play's end window once it plays out. */
        const val END_LEAD_SECONDS = 8.0
        const val SETTLE_MILLIS = 3_000L
        const val RECONNECTING = "Reconnecting…"
    }
}
