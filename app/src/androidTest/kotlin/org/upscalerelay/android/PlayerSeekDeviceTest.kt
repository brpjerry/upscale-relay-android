package org.upscalerelay.android

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.protocol.LibraryNode
import kotlin.math.abs

/**
 * Relative seeks pressed while an earlier seek is still loading. Each seek
 * reloads the stream; until the new one plays, the position mpv reports is
 * first the old one and then zero. A skip pressed in that time has to move
 * from where the user asked to be, or a second "back" lands on the first
 * one's target and, a second later, at the start of the file.
 *
 * The presses here are made back to back, which needs no timing: the first
 * has committed its target and the second must build on it. (The zero case
 * is PlaybackPositionTest's.) Needs the real relay:
 * `-e auditMediaPath <library file of 20+ minutes>`, and
 * `-e auditHost`/`-e auditPort` when it is not the default.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSeekDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val preferences by lazy { AppPreferencesStore(context) }

    @Test
    fun skipBackWhileAnEarlierSeekLoadsMovesFromItsTarget() {
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the device tests"
        }
        val path = arguments.getString("auditMediaPath")
        assumeTrue("pass -e auditMediaPath <server-library file>", !path.isNullOrBlank())
        val original = runBlocking { preferences.snapshot() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: RelayViewModel
                scenario.onActivity { model = ViewModelProvider(it)[RelayViewModel::class.java] }
                await("preferences", model) { it.preferencesLoaded }
                onMain {
                    model.setHost(arguments.getString("auditHost") ?: "192.168.0.115")
                    model.setPort(arguments.getString("auditPort") ?: "8590")
                    model.setAutoPlayNext(false)
                    model.setModel("passthrough")
                    model.setQualityTier("hevc-qp18")
                    model.connect()
                }
                await("connect", model, 30_000) { it.sessionState == SessionState.BROWSING && !it.busy }
                onMain {
                    model.openFile(LibraryNode(LibraryNode.Type.FILE, path!!.substringAfterLast('/'), path))
                }
                await("playback", model, 90_000) {
                    it.playerState == MpvPlaybackState.PLAYING && !it.busy && it.mpvMetrics.positionSeconds > 1.0
                }
                val skip = model.ui.value.skipSeconds.toDouble()
                onMain { model.seekTo(START_SECONDS) }
                awaitSettledNear(model, START_SECONDS)

                // One step back from settled playback, and forward again.
                onMain { model.skip(-1) }
                awaitSettledNear(model, START_SECONDS - skip)
                onMain { model.skip(1) }
                awaitSettledNear(model, START_SECONDS)

                // Forward, then back before the forward seek has loaded.
                onMain {
                    model.skip(1)
                    model.skip(-1)
                }
                awaitSettledNear(model, START_SECONDS)

                // Two steps back, the second on top of the first.
                onMain {
                    model.skip(-1)
                    model.skip(-1)
                }
                awaitSettledNear(model, START_SECONDS - 2 * skip)

                onMain { model.closePlayback() }
                await("close", model, 60_000) {
                    it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
                }
            }
        } finally {
            runBlocking {
                preferences.setHost(original.host)
                preferences.setPort(original.port)
                preferences.setAutoPlayNext(original.autoPlayNext)
                preferences.setModel(original.model)
                preferences.setQualityTier(original.qualityTier)
            }
        }
    }

    private fun awaitSettledNear(model: RelayViewModel, expected: Double) {
        await("playing again", model, 60_000) {
            check(it.error == null) { it.error.toString() }
            !it.seeking && it.seekTargetSeconds == null && it.playerState == MpvPlaybackState.PLAYING &&
                it.mpvMetrics.positionSeconds > 0.0
        }
        val position = model.ui.value.mpvMetrics.positionSeconds
        assertTrue(
            "expected about ${expected.toInt()} s, playing at ${position.toInt()} s",
            abs(position - expected) < TOLERANCE_SECONDS,
        )
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(
        label: String,
        model: RelayViewModel,
        timeoutMillis: Long = 20_000,
        ready: (RelayUiState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready(model.ui.value)) return
            SystemClock.sleep(20)
        }
        val state = model.ui.value
        error(
            "Timed out: $label; session=${state.sessionState} player=${state.playerState} " +
                "seeking=${state.seeking} target=${state.seekTargetSeconds} " +
                "position=${state.mpvMetrics.positionSeconds} error=${state.error}",
        )
    }

    private companion object {
        const val START_SECONDS = 600.0

        /** Keyframe spacing plus the seconds that play while a seek settles. */
        const val TOLERANCE_SECONDS = 20.0
    }
}
