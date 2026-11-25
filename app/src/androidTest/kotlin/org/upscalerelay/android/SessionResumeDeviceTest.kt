package org.upscalerelay.android

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.protocol.LibraryNode
import java.net.HttpURLConnection
import java.net.URL

/**
 * PROTOCOL.md §5 and §1.2 on the real relay: the server keeps the cache
 * filled, and playback rides out a dropout of its control connection on the
 * same session.
 *
 * The control connection goes through a proxy that can stop forwarding
 * (the server repo's stall_proxy.py, SIGUSR1/SIGUSR2) while the media
 * connections go straight to the server and survive, as they do when Wi-Fi
 * stays associated but its path goes quiet. This test cannot stall the
 * proxy itself: once it logs `READY_FOR_DROPOUT` (tag SessionResumeTest),
 * whoever runs it stops the proxy for longer than the server's heartbeat
 * window and then lets it go.
 *
 * `-e auditMediaPath <library file of 20+ minutes>`, `-e auditHost` and
 * `-e auditPort` naming the proxy, and `-e statusHost`/`-e statusPort` the
 * server itself (192.168.0.115:8590 by default).
 */
@RunWith(AndroidJUnit4::class)
class SessionResumeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    // Bugs caught: 0, False positives: 1
    // (ActivityScenario.close() on a locked tablet: leaving the player recreates
    // the Activity in portrait without setShowWhenLocked, and the scenario loses it.)
    @Test
    fun playbackRidesOutAControlDropoutOnTheSameSession() {
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the device tests"
        }
        val path = arguments.getString("auditMediaPath")
        assumeTrue("pass -e auditMediaPath <server-library file>", !path.isNullOrBlank())
        val host = arguments.getString("auditHost")
        assumeTrue("pass -e auditHost/-e auditPort naming the stall proxy", !host.isNullOrBlank())
        val statusUrl = "http://${arguments.getString("statusHost") ?: "192.168.0.115"}:" +
            "${arguments.getString("statusPort") ?: "8590"}/status"
        launchOverKeyguard().use { scenario ->
            lateinit var model: RelayViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RelayViewModel::class.java] }
            await("preferences", model) { it.preferencesLoaded }
            onMain {
                model.setHost(host!!)
                model.setPort(arguments.getString("auditPort") ?: "18590")
                model.setAutoPlayNext(false)
                model.setModel("passthrough")
                model.setQualityTier("hevc-qp18")
                model.setCacheMib(CACHE_DEFAULT_MIB)
                model.connect()
            }
            await("connect", model, 30_000) { it.sessionState == SessionState.BROWSING && !it.busy }
            onMain { model.openFile(LibraryNode(LibraryNode.Type.FILE, path!!.substringAfterLast('/'), path)) }
            await("playback", model, 90_000) {
                it.playerState == MpvPlaybackState.PLAYING && !it.busy && it.mpvMetrics.positionSeconds > 1.0
            }
            val session = model.ui.value.session
            assertNotNull("no session", session)
            // §5: the server confirmed a target, sized for the cache, well past its old 10 s.
            val opened = session!!.bufferTargetMillis
            assertNotNull("the server confirmed no buffer target", opened)
            assertTrue("opened with $opened ms", opened!! > 10_000)
            assertNotNull("the session was not opened for a resume", session.resumeToken)

            // Once mpv holds a second of video, the target follows what it holds.
            // The server must apply one of those reports, not keep the opening target.
            val reported = mutableSetOf<Long>()
            var applied: Long? = null
            await("the server applies a target sized by the cache", model, 30_000) { state ->
                state.transportStats.bufferTargetMillis?.takeIf { it != opened }?.let(reported::add)
                applied = serverTarget(statusUrl, session.sessionId)
                applied != null && applied in reported
            }
            Log.i(TAG, "buffer target: opened=$opened applied=$applied")
            assertTrue("server applies $applied ms", applied!! > 10_000)

            // Enough buffered to play through the dropout and its detection.
            await("buffer for the dropout", model, 180_000) {
                it.transportStats.reportedBufferMillis >= MIN_BUFFER_FOR_DROPOUT_MILLIS
            }
            val startedAt = SystemClock.elapsedRealtime()
            val startPosition = model.ui.value.mpvMetrics.positionSeconds
            Log.i(TAG, "READY_FOR_DROPOUT session=${session.sessionId} position=$startPosition " +
                "buffered=${model.ui.value.transportStats.reportedBufferMillis}")

            await("the lost connection is noticed", model, 180_000, guard = ::noRecoveryUi) { it.resumingConnection }
            val lostAt = model.ui.value.userPositionSeconds()
            Log.i(TAG, "RESUMING at $lostAt")
            await("the session is resumed", model, 240_000, guard = ::noRecoveryUi) { !it.resumingConnection }
            val resumedAt = model.ui.value.mpvMetrics.positionSeconds
            val elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            Log.i(TAG, "RESUMED at $resumedAt")
            val after = model.ui.value
            noRecoveryUi(after)
            assertEquals("the session changed", session.sessionId, after.session?.sessionId)
            assertEquals(MpvPlaybackState.PLAYING, after.playerState)
            // It played on through the dropout and its detection rather than
            // waiting either out: as much video as time passed, give or take
            // the one-second metrics tick.
            val playedSeconds = resumedAt - startPosition
            assertTrue("played $playedSeconds s in $elapsedSeconds s", playedSeconds >= elapsedSeconds - 3.0)

            // The resumed connection carries a seek and the teardown.
            val target = resumedAt + 60
            onMain { model.seekTo(target) }
            await("seek after the resume", model, 60_000, guard = ::noRecoveryUi) {
                !it.seeking && it.seekTargetSeconds == null && it.playerState == MpvPlaybackState.PLAYING &&
                    it.mpvMetrics.positionSeconds > target - 20
            }
            onMain { model.closePlayback() }
            await("close", model, 60_000) {
                it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
            }
            assertEquals(null, model.ui.value.error)
            assertTrue("the server still lists it", serverListed(statusUrl, session.sessionId) == false)
        }
    }

    /** Anything but a quiet resume: an error, the reconnect overlay, or a failed session. */
    private fun noRecoveryUi(state: RelayUiState) {
        check(state.error == null) { "error: ${state.error}" }
        check(state.reconnecting == null) { "reconnect overlay: ${state.reconnecting?.reason}" }
        check(state.sessionState != SessionState.FAILED) { "session failed" }
    }

    private fun status(url: String): JSONObject? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun serverTarget(url: String, sessionId: String): Long? {
        val sessions = status(url)?.optJSONArray("sessions") ?: return null
        for (index in 0 until sessions.length()) {
            val listed = sessions.getJSONObject(index)
            if (listed.optString("id") != sessionId) continue
            return listed.optJSONObject("pipeline")?.optLong("buffer_target_ms", 0)?.takeIf { it > 0 }
        }
        return null
    }

    private fun serverListed(url: String, sessionId: String): Boolean? {
        val sessions = status(url)?.optJSONArray("sessions") ?: return null
        return (0 until sessions.length()).any { sessions.getJSONObject(it).optString("id") == sessionId }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(
        label: String,
        model: RelayViewModel,
        timeoutMillis: Long = 20_000,
        guard: (RelayUiState) -> Unit = {},
        ready: (RelayUiState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.ui.value
            guard(state)
            if (ready(state)) return
            SystemClock.sleep(50)
        }
        val state = model.ui.value
        error(
            "Timed out: $label; session=${state.sessionState} player=${state.playerState} " +
                "resuming=${state.resumingConnection} reconnecting=${state.reconnecting?.reason} " +
                "position=${state.mpvMetrics.positionSeconds} buffered=${state.transportStats.reportedBufferMillis} " +
                "error=${state.error}",
        )
    }

    private companion object {
        const val TAG = "SessionResumeTest"

        /** A 45 s stall, up to 20 s for the client to notice it, and slack. */
        const val MIN_BUFFER_FOR_DROPOUT_MILLIS = 90_000L
    }
}
