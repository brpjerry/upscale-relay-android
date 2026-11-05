package org.upscalerelay.android

import android.net.ConnectivityManager
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
import kotlin.math.abs

/**
 * A playback setting changed while an open, a reconnect or a file advance is
 * under way. That work has already sent the old value, so the change must
 * follow it as one restart once it has published its session, at the
 * position it published (a resume point, the reconnect's position, the next
 * file's start), and a model or tier chosen in that time must not be put back
 * by its write-back. During an advance the file being left's session is never
 * shown as the next file's.
 *
 * Each change is made once the controller reports OPENING, which it does
 * only after the open has read its settings, so the race is the real one
 * every run. Needs the real relay: `-e auditMediaPath <library file of 7+
 * minutes>` with a next file in its folder, `-e model <an upscaling model the
 * server has>` when not 2x_AnimeJaNai_HD_V3_Compact, and `-e auditHost`/
 * `-e auditPort` when it is not the default. Runs on passthrough except for
 * the model change, which plays the upscaling model for a few seconds. The
 * reconnect test switches Wi-Fi off and on again, so adb must be on USB.
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
        saveResumePoint(model, path)
        val opened = openAndChange(model, path) { model.setQualityTier(chosenTier) }
        val sessions = awaitRestart(model, opened, "the restart for the quality chosen while the file opened")
        assertTrue(model.ui.value.sessionDescription, model.ui.value.sessionDescription.contains(" · $chosenTier · "))
        assertEquals(chosenTier, model.ui.value.qualityTier)
        assertEquals("one restart after the open: $sessions", 2, sessions.size)
        // The open resumed; the restart must not lose that for mpv's position before it.
        assertPlayingNear(model, RESUME_SECONDS)
        close(model)
    }

    @Test
    fun aModelChosenWhileAFileOpensSurvivesItsPublish() = withPlayer(autoPlayNext = false) { model, path, _ ->
        val chosen = arguments.getString("model") ?: "2x_AnimeJaNai_HD_V3_Compact"
        check(chosen != PASSTHROUGH_MODEL) { "-e model must name an upscaling model, not $chosen" }
        saveResumePoint(model, path)
        val opened = openAndChange(model, path) { model.setModel(chosen) }
        // The open published passthrough, which it read; the choice made
        // since stays the selection and the saved model all the while.
        val sessions = awaitRestart(model, opened, "the restart for the model chosen while the file opened") {
            check(it.selectedModel == chosen) { "the open put back ${it.selectedModel} over $chosen" }
        }
        assertEquals(chosen, model.ui.value.relaySession?.model)
        assertEquals(chosen, runBlocking { preferences.snapshot() }.model)
        assertEquals("one restart after the open: $sessions", 2, sessions.size)
        assertPlayingNear(model, RESUME_SECONDS)
        close(model)
    }

    @Test
    fun aQualityChosenWhileAReconnectOpensRestartsItOnceItPlays() = withPlayer(autoPlayNext = false) { model, path, tiers ->
        val (openTier, chosenTier) = tiers
        onMain { model.setQualityTier(openTier) }
        saveResumePoint(model, path)
        open(model, path)
        val lost = checkNotNull(model.ui.value.session?.sessionId)
        try {
            setWifi(enabled = false)
            await("the dead connection to start a reconnect", model, 120_000) {
                check(it.error == null) { it.error.toString() }
                it.reconnecting?.reason == RECONNECTING
            }
            val lostAt = model.ui.value.userPositionSeconds()
            setWifi(enabled = true)
            await("the reconnect to reach the server", model, 180_000, pollMillis = 2) {
                check(it.error == null) { it.error.toString() }
                check(it.session?.sessionId == lost) { "the reconnect published before the change could be made" }
                it.reconnecting?.reason == RECONNECTING && it.sessionState == SessionState.OPENING
            }
            onMain { model.setQualityTier(chosenTier) }
            await("the reconnect to publish", model, 120_000) {
                check(it.error == null) { it.error.toString() }
                it.session != null && it.session?.sessionId != lost && it.reconnecting?.reason != RECONNECTING
            }
            val reconnected = checkNotNull(model.ui.value.session?.sessionId)
            // The reconnect sent the old tier; its write-back must not undo the new one.
            val sessions = awaitRestart(model, reconnected, "the restart for the quality chosen while it reconnected") {
                check(it.qualityTier == chosenTier) { "the reconnect put back ${it.qualityTier} over $chosenTier" }
            }
            assertTrue(model.ui.value.sessionDescription, model.ui.value.sessionDescription.contains(" · $chosenTier · "))
            assertEquals("one restart after the reconnect: $sessions", 2, sessions.size)
            assertPlayingNear(model, lostAt)
        } finally {
            setWifi(enabled = true)
        }
        close(model)
    }

    @Test
    fun aQualityChosenDuringAutoPlaysAdvanceRestartsTheNextFile() =
        withPlayer(autoPlayNext = true) { model, path, tiers -> advanceAndChange(model, path, tiers, nextButton = false) }

    @Test
    fun aQualityChosenAfterTheNextButtonRestartsTheNextFileFromItsStart() =
        withPlayer(autoPlayNext = true) { model, path, tiers -> advanceAndChange(model, path, tiers, nextButton = true) }

    /**
     * Plays [path] to its end (auto-play) or presses next past its last
     * chapter, and chooses another quality once the next file's open has sent
     * the old one. The next file must play from its start with the new tier,
     * after one restart, and the file being left's session must never be on
     * screen as the next one's. The next button stops the metrics before the
     * advance, so mpv's last position is still the old file's then.
     */
    private fun advanceAndChange(model: RelayViewModel, path: String, tiers: Pair<String, String>, nextButton: Boolean) {
        val (openTier, chosenTier) = tiers
        onMain { model.setQualityTier(openTier) }
        open(model, path)
        val left = checkNotNull(model.ui.value.session)
        val duration = checkNotNull(left.durationSeconds) { "the server reported no duration" }
        if (nextButton) {
            // Past the last chapter, so next finishes the file rather than seeking.
            val lastChapter = left.chapters.maxOfOrNull { it.startSeconds } ?: 0.0
            val target = maxOf(duration - NEXT_LEAD_SECONDS, lastChapter + 2.0)
            check(target < duration - 5.0) { "the last chapter starts too close to the end: $lastChapter of $duration" }
            onMain { model.seekTo(target) }
            await("settled before the end", model, 60_000) {
                check(it.error == null) { it.error.toString() }
                !it.seeking && it.seekTargetSeconds == null && it.playerState == MpvPlaybackState.PLAYING &&
                    it.mpvMetrics.positionSeconds > target - 20
            }
            onMain { model.chapterStep(1) }
        } else {
            onMain { model.seekTo(duration - END_LEAD_SECONDS) }
        }
        await("the next file to start opening", model, 90_000) {
            check(it.error == null) { it.error.toString() }
            it.playingPath != path && it.sessionState == SessionState.OPENING
        }
        val next = checkNotNull(model.ui.value.playingPath)
        onMain { model.setQualityTier(chosenTier) }
        val seen = linkedSetOf<String>()
        fun watch(state: RelayUiState) {
            check(state.error == null) { state.error.toString() }
            check(state.reconnecting?.reason != RECONNECTING) { "a reconnect ran during the advance" }
            check(state.playingPath == next) { "playing ${state.playingPath}, expected $next" }
            // The file being left is never described as the next one.
            check(state.session?.sessionId != left.sessionId) { "the session of $path was shown for $next" }
            state.session?.sessionId?.let(seen::add)
        }
        await("the next file to play with the quality chosen during the advance", model, 120_000) {
            watch(it)
            it.playerState == MpvPlaybackState.PLAYING && it.reconnecting == null &&
                it.sessionDescription.contains(" · $chosenTier · ") && it.mpvMetrics.positionSeconds > 0.5
        }
        // Nothing follows once it plays: one restart, not one per change.
        val settleUntil = SystemClock.elapsedRealtime() + SETTLE_MILLIS
        while (SystemClock.elapsedRealtime() < settleUntil) {
            watch(model.ui.value)
            SystemClock.sleep(20)
        }
        // The advance's session, opened with the old quality, then the restart's.
        assertEquals("one restart after the advance: $seen", 2, seen.size)
        assertPlayingNear(model, 0.0)
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
        fun watch(state: RelayUiState) {
            check(state.error == null) { state.error.toString() }
            check(state.reconnecting?.reason != RECONNECTING) { "a reconnect ran during the restart" }
            each(state)
            state.session?.sessionId?.let(seen::add)
        }
        await(label, model, 120_000) {
            watch(it)
            it.session?.sessionId != previous && it.playerState == MpvPlaybackState.PLAYING &&
                it.reconnecting == null && !it.busy && it.mpvMetrics.positionSeconds > 0.5
        }
        // Nothing follows once it plays: one restart, not one per change.
        val settleUntil = SystemClock.elapsedRealtime() + SETTLE_MILLIS
        while (SystemClock.elapsedRealtime() < settleUntil) {
            watch(model.ui.value)
            SystemClock.sleep(20)
        }
        assertEquals(null, model.ui.value.reconnecting)
        return seen
    }

    /** Saves a resume point the next open of [path] starts from. */
    private fun saveResumePoint(model: RelayViewModel, path: String) {
        val key = serverHistoryKey(model.ui.value.historyScope, path)
        runBlocking { preferences.setPlaybackPosition(key, RESUME_SECONDS, SAVED_DURATION_SECONDS) }
        await("the resume point", model) { it.playbackProgress[key]?.positionSeconds == RESUME_SECONDS }
    }

    /** Playback moves on, from about [expected]: not held there, as it is while the original's audio attaches. */
    private fun assertPlayingNear(model: RelayViewModel, expected: Double) {
        val first = model.ui.value.mpvMetrics.positionSeconds
        await("playback to move on from ${first.toInt()} s", model, 30_000) {
            check(it.error == null) { it.error.toString() }
            it.mpvMetrics.positionSeconds > first + 1.0
        }
        val position = model.ui.value.mpvMetrics.positionSeconds
        assertTrue(
            "expected about ${expected.toInt()} s, playing at ${position.toInt()} s",
            abs(position - expected) < TOLERANCE_SECONDS,
        )
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
                // A 4K file would otherwise play passthrough under any model.
                model.setPassthrough4k(false)
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

    private fun setWifi(enabled: Boolean) {
        val command = "cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}"
        instrumentation.uiAutomation.executeShellCommand(command).close()
        if (enabled) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connectivity.activeNetwork == null) return
            SystemClock.sleep(100)
        }
        error("Wi-Fi did not switch off")
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
                "path=${state.playingPath} model=${state.relaySession?.model} position=${state.mpvMetrics.positionSeconds} " +
                "description=${state.sessionDescription} reconnecting=${state.reconnecting} error=${state.error}",
        )
    }

    private companion object {
        /** How far before the end auto-play's test seeks: past auto-play's end window once it plays out. */
        const val END_LEAD_SECONDS = 8.0

        /** How far before the end the next button is pressed, once past the last chapter. */
        const val NEXT_LEAD_SECONDS = 25.0
        const val RESUME_SECONDS = 300.0
        const val SAVED_DURATION_SECONDS = 1_440.0

        /** Keyframe spacing plus the seconds that play while a restart settles. */
        const val TOLERANCE_SECONDS = 20.0
        const val SETTLE_MILLIS = 3_000L
        const val RECONNECTING = "Reconnecting…"
    }
}
