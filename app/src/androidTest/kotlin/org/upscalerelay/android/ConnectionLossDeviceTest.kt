package org.upscalerelay.android

import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.protocol.LibraryNode

/**
 * Playback has to come back by itself after its control connection dies,
 * which is what a tablet that slept through a paused episode wakes up to.
 * The old session can no longer be asked to tear down, so the replacement
 * connection has to confirm with the server that it is gone and resume.
 *
 * Wi-Fi is switched off until the app notices the dead connection and then
 * back on; it is always switched back on. Needs the real relay:
 * `-e auditMediaPath <library file of 20+ minutes>`, and
 * `-e auditHost`/`-e auditPort` when it is not the default.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionLossDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val preferences by lazy { AppPreferencesStore(context) }

    @Test
    fun playbackResumesAfterItsControlConnectionDies() {
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
                onMain { model.seekTo(START_SECONDS) }
                await("settled at the start point", model, 60_000) {
                    !it.seeking && it.seekTargetSeconds == null && it.playerState == MpvPlaybackState.PLAYING &&
                        it.mpvMetrics.positionSeconds > START_SECONDS - 20
                }
                val lostSession = model.ui.value.session?.sessionId

                setWifi(enabled = false)
                await("the dead connection is noticed", model, 90_000) {
                    it.reconnecting != null || it.error != null || it.sessionState == SessionState.FAILED
                }
                val lostAt = model.ui.value.userPositionSeconds()
                setWifi(enabled = true)

                await("playback resumed on a new session", model, 180_000) {
                    // The one outcome nothing retries: the hard stop this test is about.
                    check(it.error?.contains("did not confirm") != true) { "resume refused: ${it.error}" }
                    it.reconnecting == null && it.error == null && !it.seeking &&
                        it.playerState == MpvPlaybackState.PLAYING &&
                        it.session != null && it.session?.sessionId != lostSession &&
                        it.mpvMetrics.positionSeconds > 0.0
                }
                val resumedAt = model.ui.value.mpvMetrics.positionSeconds
                assertNotEquals(lostSession, model.ui.value.session?.sessionId)
                assertTrue(
                    "lost at ${lostAt.toInt()} s, resumed at ${resumedAt.toInt()} s",
                    resumedAt > lostAt - TOLERANCE_SECONDS && resumedAt < lostAt + TOLERANCE_SECONDS,
                )

                onMain { model.closePlayback() }
                await("close", model, 60_000) {
                    it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
                }
            }
        } finally {
            setWifi(enabled = true)
            runBlocking {
                preferences.setHost(original.host)
                preferences.setPort(original.port)
                preferences.setAutoPlayNext(original.autoPlayNext)
                preferences.setModel(original.model)
                preferences.setQualityTier(original.qualityTier)
            }
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
        ready: (RelayUiState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready(model.ui.value)) return
            SystemClock.sleep(50)
        }
        val state = model.ui.value
        error(
            "Timed out: $label; session=${state.sessionState} player=${state.playerState} " +
                "reconnecting=${state.reconnecting?.reason} position=${state.mpvMetrics.positionSeconds} " +
                "error=${state.error}",
        )
    }

    private companion object {
        const val START_SECONDS = 300.0

        /** Keyframe spacing plus what plays while the loss is noticed and repaired. */
        const val TOLERANCE_SECONDS = 45.0
    }
}
