package org.upscalerelay.android

import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import java.io.File
import kotlin.math.abs

/**
 * End to end against a real relay that advertises server_id and honours
 * open_session.video.sample_aspect_ratio. Skipped unless pointed at one:
 * `-e e2eHost <host> -e e2ePort <port>`. Add `-e e2eBitstreamAspect true` when
 * the server also reads a bitstream-only aspect (Matroska DisplayUnit 4).
 */
@RunWith(AndroidJUnit4::class)
class ServerE2EDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val preferences by lazy { AppPreferencesStore(context) }

    @Test
    fun serverIdScopesTheHistoryShown() {
        withServer { model ->
            val id = model.ui.value.capabilities?.serverId
            assertNotNull("the server advertises server_id", id)
            assertEquals("id:$id", model.ui.value.historyScope)
        }
    }

    @Test
    fun anamorphicUplinksAreFittedByDisplayAspect() {
        val clips = buildList {
            add("audit-sar-16x15.mp4")
            add("audit-sar-16x15-display.mkv")
            if (arguments.getString("e2eBitstreamAspect") == "true") add("audit-sar-16x15.mkv")
        }
        withServer { model ->
            for (name in clips) {
                val file = File(context.filesDir, name)
                assumeTrue("push $name (tools/make_anamorphic_clips.py)", file.isFile)
                onMain { model.openLocalDocument(Uri.fromFile(file).toString()) }
                await("$name playing", model, 90_000) {
                    check(it.error == null) { "$name: ${it.error}" }
                    it.playerState == MpvPlaybackState.PLAYING && !it.busy && it.session != null &&
                        it.mpvMetrics.positionSeconds > 0.5
                }
                val session = model.ui.value.session!!
                val aspect = session.downlinkWidth.toDouble() / session.downlinkHeight
                // 720x576 at 16:15 shows 4:3; read as square pixels it would be 5:4.
                assertTrue(
                    "$name: downlink ${session.downlinkWidth}x${session.downlinkHeight} is not 4:3",
                    abs(aspect - 4.0 / 3.0) < 0.01,
                )
                onMain { model.closePlayback() }
                await("$name closed", model, 60_000) {
                    it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
                }
            }
        }
    }

    /**
     * An untagged SD source relayed at an HD size must keep its colours: the
     * server tags the output as the SD guess mpv makes for the original, so
     * relayed and direct playback of a flat, saturated clip render the same.
     * Untagged HD output would be read as BT.709 and shift hue.
     * Needs tools/make_colour_clip.py's clip; `-e e2eColour true` enables it.
     */
    @Test
    fun untaggedSdColoursMatchDirectPlayback() {
        assumeTrue("pass -e e2eColour true", arguments.getString("e2eColour") == "true")
        val file = File(context.filesDir, "audit-colour-sd.mp4")
        assumeTrue("push audit-colour-sd.mp4 (tools/make_colour_clip.py)", file.isFile)
        withServer { model ->
            onMain { model.openLocalDocument(Uri.fromFile(file).toString()) }
            await("relayed playback", model, 90_000) {
                check(it.error == null) { "relay: ${it.error}" }
                it.playerState == MpvPlaybackState.PLAYING && !it.busy && it.mpvMetrics.positionSeconds > 1.0
            }
            val relayed = settledColour()
            onMain { model.playLocalFallback() }
            await("direct playback", model, 30_000) {
                it.directLocalFallback && it.playerState == MpvPlaybackState.PLAYING
            }
            val direct = settledColour()
            onMain { model.closePlayback() }
            await("close", model, 60_000) { it.playingPath == null && !it.busy }
            val worst = (0 until 3).maxOf { abs(relayed[it] - direct[it]) }
            Log.i(TAG, "colour relayed=${relayed.toList()} direct=${direct.toList()} source=[200, 60, 90]")
            assertTrue(
                "relayed ${relayed.toList()} differs from direct ${direct.toList()} by $worst",
                worst <= COLOUR_TOLERANCE,
            )
        }
    }

    /**
     * The average colour of a 21x21 patch left of centre, sampled after the
     * player controls have hidden (they cover the centre and the edges).
     */
    private fun settledColour(): IntArray {
        SystemClock.sleep(6_000)
        instrumentation.waitForIdleSync()
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val cx = shot.width / 4
            val cy = shot.height / 2
            val sums = IntArray(3)
            var count = 0
            for (x in cx - 10..cx + 10) {
                for (y in cy - 10..cy + 10) {
                    val pixel = shot.getPixel(x, y)
                    sums[0] += Color.red(pixel)
                    sums[1] += Color.green(pixel)
                    sums[2] += Color.blue(pixel)
                    count += 1
                }
            }
            return IntArray(3) { sums[it] / count }
        } finally {
            shot.recycle()
        }
    }

    private fun withServer(body: (RelayViewModel) -> Unit) {
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the audit tests"
        }
        val host = arguments.getString("e2eHost")
        val port = arguments.getString("e2ePort")
        assumeTrue("pass -e e2eHost <host> -e e2ePort <port>", !host.isNullOrBlank() && !port.isNullOrBlank())
        val original = runBlocking { preferences.snapshot() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: RelayViewModel
                scenario.onActivity { model = ViewModelProvider(it)[RelayViewModel::class.java] }
                await("preferences", model) { it.preferencesLoaded }
                onMain {
                    model.setHost(host!!)
                    model.setPort(port!!)
                    model.setAutoPlayNext(false)
                    model.setModel("passthrough")
                    model.setQualityTier("hevc-qp18")
                    model.setFitMode("fit")
                    model.connect()
                }
                await("connect to $host:$port", model, 30_000) {
                    it.sessionState == SessionState.BROWSING && !it.busy && it.capabilities != null &&
                        it.port == port
                }
                body(model)
            }
        } finally {
            runBlocking {
                preferences.setHost(original.host)
                preferences.setPort(original.port)
                preferences.setAutoPlayNext(original.autoPlayNext)
                preferences.setModel(original.model)
                preferences.setQualityTier(original.qualityTier)
                preferences.setFitMode(original.fitMode)
            }
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private companion object {
        const val TAG = "ServerE2E"

        /** 8-bit rounding through two conversions; a BT.601/709 mix-up is ~10-20. */
        const val COLOUR_TOLERANCE = 4
    }

    private fun await(
        label: String,
        model: RelayViewModel,
        timeoutMillis: Long = 20_000,
        ready: (RelayUiState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready(model.ui.value)) return
            SystemClock.sleep(100)
        }
        val state = model.ui.value
        error(
            "Timed out: $label; session=${state.sessionState} player=${state.playerState} " +
                "playing=${state.playingPath} busy=${state.busy} error=${state.error} " +
                "downlink=${state.session?.downlinkWidth}x${state.session?.downlinkHeight}",
        )
    }
}
