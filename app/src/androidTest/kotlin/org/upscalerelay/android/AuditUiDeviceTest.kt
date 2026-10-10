package org.upscalerelay.android

import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
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
 * UI regressions driven through Compose on the debug app: the October 2026
 * audit's, and the player controls'. Saved host, port, destination and
 * auto-play are put back.
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
     * releasing or cancelling it lets them hide again.
     */
    @Test
    fun seekBarDragOutlastsTheControlsTimeout() = withClipPlaying {
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
    }

    /**
     * The lock takes one press, in both directions, wherever on it the press
     * lands. An icon button is hit only inside its 40dp circle when something
     * else lies under the rest of it, and the picture's tap handler did: a
     * press on the lock's edge hid the controls instead (locked, it hid the
     * unlock button), and the lock then took two more presses.
     */
    @Test
    fun lockTakesOnePressWhereverOnItThePressLands() = withClipPlaying {
        compose.mainClock.autoAdvance = false
        val edges: List<TouchInjectionScope.() -> Offset> = listOf(
            { Offset(3f, 3f) },
            { Offset(width - 3f, 3f) },
            { Offset(3f, height - 3f) },
            { Offset(width - 3f, height - 3f) },
            { center },
        )
        for (edge in edges) {
            showControls()
            compose.onNodeWithContentDescription(LOCK).performTouchInput { click(edge()) }
            settle()
            compose.onNodeWithContentDescription(UNLOCK).assertExists()
            compose.onNodeWithContentDescription("Playback position").assertDoesNotExist()
            compose.onNodeWithContentDescription(UNLOCK).performTouchInput { click(edge()) }
            settle()
            compose.onNodeWithContentDescription(LOCK).assertExists()
        }
    }

    /**
     * A press that misses the controls does not take them away. On a control
     * bar it is the bar's, not a tap on the picture underneath. Locked, a
     * press on the picture only ever brings the unlock button up, for a fresh
     * timeout each time. And the controls still hide by themselves afterwards.
     */
    @Test
    fun aMissedPressLeavesTheControlsWhereTheyAre() = withClipPlaying {
        compose.mainClock.autoAdvance = false
        showControls()
        compose.onAllNodesWithText("audit-clip-150s.mp4", substring = true).onFirst()
            .performTouchInput { click() }
        settle()
        compose.onNodeWithContentDescription("Playback position").assertExists()

        compose.onNodeWithContentDescription(LOCK).performTouchInput { click() }
        settle()
        compose.onNodeWithContentDescription(UNLOCK).assertExists()
        compose.onRoot().performTouchInput { click(center) }
        settle()
        compose.onNodeWithContentDescription(UNLOCK).assertExists()
        // Most of one timeout, a press, most of another: still up.
        compose.mainClock.advanceTimeBy(3_000)
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithContentDescription(UNLOCK).assertExists()
        compose.mainClock.advanceTimeBy(15_000)
        compose.onNodeWithContentDescription(UNLOCK).assertDoesNotExist()

        compose.onRoot().performTouchInput { click(center) }
        settle()
        compose.onNodeWithContentDescription(UNLOCK).performTouchInput { click() }
        settle()
        compose.onNodeWithContentDescription("Playback position").assertExists()
        compose.mainClock.advanceTimeBy(15_000)
        compose.onNodeWithContentDescription("Playback position").assertDoesNotExist()
    }

    /**
     * What the tablet does to about half of all presses on the top bar. Its
     * touch controller calls the press a palm as the finger lifts, and the
     * system delivers a cancel marked FLAG_CANCELED and, a frame or two
     * later, the up. The button lit up and nothing happened. The player takes
     * that for the press it was, in both directions. The timings are the ones
     * recorded from the tablet.
     */
    @Test
    fun aPressTheTabletCancelsAsAPalmStillLocks() = withClipPlaying {
        compose.mainClock.autoAdvance = false
        showControls()
        palmCancelledPress(centreInWindow(LOCK), cancels = listOf(90L), upAfter = 105L)
        settle()
        compose.onNodeWithContentDescription(UNLOCK).assertExists()
        palmCancelledPress(centreInWindow(UNLOCK), cancels = listOf(92L), upAfter = 108L)
        settle()
        compose.onNodeWithContentDescription(LOCK).assertExists()
    }

    /**
     * A palm is still a palm. One that stays down goes on being cancelled,
     * sample after sample, and presses nothing. And below the top strip the
     * tablet's verdict stands even for a press that lifts at once: on the
     * picture that would otherwise be a tap, and hide the controls.
     */
    @Test
    fun aRealPalmIsStillIgnored() = withClipPlaying {
        compose.mainClock.autoAdvance = false
        showControls()
        palmCancelledPress(centreInWindow(LOCK), cancels = listOf(197L, 205L, 222L, 230L, 247L), upAfter = 1_255L)
        settle()
        compose.onNodeWithContentDescription(UNLOCK).assertDoesNotExist()
        compose.onNodeWithContentDescription(LOCK).assertExists()

        val picture = compose.onRoot().fetchSemanticsNode().boundsInWindow.center
        palmCancelledPress(picture, cancels = listOf(90L), upAfter = 105L)
        settle()
        compose.onNodeWithContentDescription("Playback position").assertExists()
    }

    private fun centreInWindow(description: String): Offset =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInWindow.center

    /**
     * Hands the Activity what the system hands it for a touch the controller
     * flagged as a palm: down, a cancel marked FLAG_CANCELED for each sample
     * from the flag on, then the up. Times are milliseconds after the down.
     */
    private fun palmCancelledPress(at: Offset, cancels: List<Long>, upAfter: Long) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, after: Long, flags: Int = 0) {
            val properties = arrayOf(
                MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            )
            val coordinates = arrayOf(
                MotionEvent.PointerCoords().apply { x = at.x; y = at.y; pressure = 1f; size = 1f },
            )
            val event = MotionEvent.obtain(
                down, down + after, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, flags,
            )
            compose.runOnUiThread { compose.activity.dispatchTouchEvent(event) }
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0)
        cancels.forEach { send(MotionEvent.ACTION_CANCEL, it, FLAG_CANCELED) }
        send(MotionEvent.ACTION_UP, upAfter)
        // Past the wait for an up that a held cancel is given.
        SystemClock.sleep(250)
    }

    /**
     * Plays the generated clip through the original-file fallback, so the
     * player and its controls are up without a relay server, and closes it
     * afterwards.
     */
    private fun withClipPlaying(body: () -> Unit) {
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
                body()
            } finally {
                compose.mainClock.autoAdvance = true
                onModel { it.closePlayback() }
                awaitModel(30_000) { it.playingPath == null }
                restorePreferences()
            }
        }
    }

    /** Lets a press take effect, well inside any timeout. */
    private fun settle() = compose.mainClock.advanceTimeBy(300)

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
        const val LOCK = "Lock player controls"

        /** MotionEvent.FLAG_CANCELED, public from API 33. */
        const val FLAG_CANCELED = 0x20
        const val UNLOCK = "Unlock player controls"
    }
}
