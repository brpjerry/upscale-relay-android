package org.upscalerelay.android

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import java.io.File
import java.net.ServerSocket

/**
 * UI regressions for the October 2026 audit, driven through Compose on the
 * debug app. Saved host, port, destination and auto-play are put back.
 */
@RunWith(AndroidJUnit4::class)
class AuditUiDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences by lazy { AppPreferencesStore(context) }
    private lateinit var original: AppPreferences

    @Before
    fun rememberPreferences() {
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the audit tests"
        }
        original = runBlocking { preferences.snapshot() }
    }

    @Test
    fun invalidPortIsReportedOnTheSettingsScreen() {
        withSettings {
            textField(PORT).performTextReplacement("0")
            connectButton().performClick()
            awaitText("Enter a valid host and port.")
            compose.onNodeWithText("Enter a valid host and port.").assertIsDisplayed()
            assertEquals(TabletDestination.SETTINGS, model().ui.value.destination)
        }
    }

    @Test
    fun unreachableServerIsReportedOnTheSettingsScreen() {
        val closedPort = ServerSocket(0).use { it.localPort }
        withSettings {
            textField(HOST).performTextReplacement("127.0.0.1")
            textField(PORT).performTextReplacement(closedPort.toString())
            connectButton().performClick()
            val message = "Could not reach the server at 127.0.0.1:$closedPort."
            awaitText(message, timeoutMillis = 30_000)
            compose.onNodeWithText(message).assertIsDisplayed()
            assertEquals(TabletDestination.SETTINGS, model().ui.value.destination)
        }
    }

    /**
     * Two servers can hold different media at the same path. What was
     * watched on one must not show as watched on the other, and must still be
     * there on returning to the first.
     */
    @Test
    fun watchedStateBelongsToTheServerItWasWatchedOn() {
        val library = mapOf("" to listOf(FakeEntry("show.mkv")))
        // Long press toggles, so start (and leave) both entries unwatched.
        val entries = listOf("id:alpha", "id:bravo").map { serverHistoryKey(it, "show.mkv") }
        fun forget() = runBlocking { entries.forEach { preferences.clearPlaybackPosition(it) } }
        forget()
        FakeRelay(library = library, serverId = "alpha").use { alpha ->
            FakeRelay(library = library, serverId = "bravo").use { bravo ->
                try {
                    awaitModel { it.preferencesLoaded }
                    onModel { it.selectDestination(TabletDestination.SERVER) }
                    connectTo(alpha)
                    compose.onAllNodesWithText("show.mkv").onFirst().performTouchInput { longClick() }
                    awaitText("100% watched", substring = true)

                    connectTo(bravo)
                    compose.waitForIdle()
                    assertTrue(
                        "the other server's show.mkv must not read as watched",
                        compose.onAllNodes(hasText("watched", substring = true)).fetchSemanticsNodes().isEmpty(),
                    )

                    connectTo(alpha)
                    awaitText("100% watched", substring = true)
                } finally {
                    restorePreferences()
                    forget()
                }
            }
        }
    }

    /** Points the app at [relay] and waits for its listing. */
    private fun connectTo(relay: FakeRelay) {
        onModel {
            it.setHost("127.0.0.1")
            it.setPort(relay.port.toString())
            it.connect()
        }
        awaitModel {
            it.sessionState == SessionState.BROWSING && !it.busy && !it.libraryLoading &&
                it.port == relay.port.toString() &&
                it.currentDirectory?.children?.any { child -> child.name == "show.mkv" } == true
        }
        awaitText("show.mkv")
    }

    /**
     * A scrub held past the auto-hide timeout keeps the controls up, and
     * releasing or cancelling it lets them hide again. Plays the generated
     * clip through the original-file fallback, so no relay server is needed.
     */
    @Test
    fun seekBarDragOutlastsTheControlsTimeout() {
        val file = File(context.filesDir, "audit-clip-150s.mp4")
        assumeTrue("push audit-clip-150s.mp4 into the debug app's files directory", file.isFile)
        val uri = Uri.fromFile(file).toString()
        FakeRelay().use { relay ->
            try {
                awaitModel { it.preferencesLoaded }
                onModel {
                    it.setHost("127.0.0.1")
                    it.setPort(relay.port.toString())
                    it.setAutoPlayNext(false)
                    it.connect()
                }
                awaitModel { it.sessionState == SessionState.BROWSING && !it.busy && it.capabilities != null }
                onModel { it.openLocalDocument(uri) }
                awaitModel(30_000) { !it.busy && it.error != null && it.playingPath != null }
                onModel { it.playLocalFallback() }
                awaitModel(30_000) {
                    it.directLocalFallback && it.playerState == MpvPlaybackState.PLAYING &&
                        it.mpvMetrics.positionSeconds > 1.0
                }

                for (release in listOf("up", "cancel")) {
                    compose.mainClock.autoAdvance = false
                    showControls()
                    val bar = compose.onNodeWithContentDescription("Playback position")
                    // Pointer handling needs the clock running; the timeout
                    // below is then stepped by hand.
                    compose.mainClock.autoAdvance = true
                    bar.performTouchInput {
                        down(Offset(width * 0.2f, centerY))
                        repeat(4) { moveBy(Offset(viewConfiguration.touchSlop, 0f)) }
                    }
                    compose.waitForIdle()
                    compose.mainClock.autoAdvance = false
                    check(model().ui.value.seekPreviewSeconds != null) { "the drag was not taken as a scrub" }
                    compose.mainClock.advanceTimeBy(15_000)
                    // Still scrubbing well past the timeout: the bar is there.
                    compose.onNodeWithContentDescription("Playback position").assertExists()
                    bar.performTouchInput {
                        moveBy(Offset(40f, 0f))
                        if (release == "up") up() else cancel()
                    }
                    compose.mainClock.advanceTimeBy(15_000)
                    compose.onNodeWithContentDescription("Playback position").assertDoesNotExist()
                }
            } finally {
                compose.mainClock.autoAdvance = true
                onModel { it.closePlayback() }
                awaitModel(30_000) { it.playingPath == null }
                restorePreferences()
            }
        }
    }

    private fun showControls() {
        repeat(3) {
            val shown = compose.onAllNodes(hasContentDescription("Playback position"))
                .fetchSemanticsNodes().isNotEmpty()
            if (shown) return
            compose.onRoot().performTouchInput { click(center) }
            // Past the double-tap window, so the tap counts as one.
            compose.mainClock.advanceTimeBy(1_000)
        }
        compose.onNodeWithContentDescription("Playback position").assertExists()
    }

    private fun withSettings(body: () -> Unit) {
        try {
            awaitModel { it.preferencesLoaded }
            onModel { it.selectDestination(TabletDestination.SETTINGS) }
            compose.waitForIdle()
            body()
        } finally {
            restorePreferences()
        }
    }

    private fun restorePreferences() = runBlocking {
        preferences.setHost(original.host)
        preferences.setPort(original.port)
        preferences.setAutoPlayNext(original.autoPlayNext)
        preferences.setLastDestination(original.lastDestination)
    }

    /** The Settings connection fields, in screen order. */
    private fun textField(index: Int) = compose.onAllNodes(hasSetTextAction())[index]

    private fun connectButton() = compose.onAllNodes(
        hasText("Connect") and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
    ).onFirst()

    private fun awaitText(text: String, timeoutMillis: Long = 10_000, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun model(): RelayViewModel {
        lateinit var model: RelayViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RelayViewModel::class.java] }
        return model
    }

    private fun onModel(action: (RelayViewModel) -> Unit) {
        val model = model()
        compose.runOnUiThread { action(model) }
    }

    private fun awaitModel(timeoutMillis: Long = 20_000, ready: (RelayUiState) -> Boolean) {
        val model = model()
        compose.waitUntil(timeoutMillis) { ready(model.ui.value) }
    }

    private companion object {
        const val HOST = 0
        const val PORT = 1
    }
}
