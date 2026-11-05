package org.upscalerelay.android

import android.os.SystemClock
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.PASSTHROUGH_MODEL
import org.upscalerelay.client.SessionState
import org.upscalerelay.client.is4kOrHigher
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.protocol.LibraryNode
import java.io.File

/**
 * "Use passthrough for 4K or higher resolution videos" against the real
 * relay. A 2160p library file, whose size is read through /media before the
 * open, plays with passthrough in place of the chosen model, and keeps it
 * through a seek and a restart for another setting; the chosen model stays
 * the one saved; another upscaling model leaves that stream alone. A 1080p
 * file plays with the chosen model, and turning the option off leaves its
 * stream alone. With `-e uhdLocalFixture <4K file in the app's files>`
 * (`dv81.mkv`, cut from the 2160p file), a local 4K uplink plays with
 * passthrough too, through a quality restart.
 *
 * Needs `-e uhdMediaPath <2160p library file>`, `-e fhdMediaPath <1080p
 * library file>`, and `-e model <an upscaling model the server has>` when not
 * 2x_AnimeJaNai_HD_V3_Compact; `-e auditHost`/`-e auditPort` when the relay
 * is not the default. Turning the option off on the 4K file would open the
 * model on 4K frames, well below realtime, so this test does not.
 */
@RunWith(AndroidJUnit4::class)
class Passthrough4kDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val preferences by lazy { AppPreferencesStore(context) }

    @Test
    fun aFourKLibraryFilePlaysWithPassthroughAndA1080pOneWithTheModel() {
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the device tests"
        }
        val uhd = arguments.getString("uhdMediaPath")
        val fhd = arguments.getString("fhdMediaPath")
        assumeTrue("pass -e uhdMediaPath and -e fhdMediaPath <library files>", !uhd.isNullOrBlank() && !fhd.isNullOrBlank())
        val chosen = arguments.getString("model") ?: "2x_AnimeJaNai_HD_V3_Compact"
        check(chosen != PASSTHROUGH_MODEL) { "-e model must name an upscaling model, not $chosen" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: RelayViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RelayViewModel::class.java] }
            await("preferences", model) { it.preferencesLoaded }
            onMain {
                model.setHost(arguments.getString("auditHost") ?: "192.168.0.115")
                model.setPort(arguments.getString("auditPort") ?: "8590")
                model.setAutoPlayNext(false)
                model.setQualityTier("hevc-qp18")
                model.setPassthrough4k(true)
                model.connect()
            }
            await("connect", model, 30_000) { it.sessionState == SessionState.BROWSING && !it.busy }
            onMain { model.setModel(chosen) }
            assertEquals("the server does not advertise $chosen", chosen, model.ui.value.selectedModel)
            val other = model.ui.value.capabilities?.models.orEmpty().map { it.name }
                .first { it != chosen && it != PASSTHROUGH_MODEL }

            open(model, uhd!!)
            assertPassthroughFor4k(model, chosen)
            onMain { model.seekTo(SEEK_SECONDS) }
            awaitPlaying(model, "the seek") { !it.seeking && it.seekTargetSeconds == null }
            assertPassthroughFor4k(model, chosen)
            val opened = model.ui.value.session?.sessionId
            onMain { model.setQualityTier("hevc-qp14") }
            awaitPlaying(model, "the restart for a new quality") {
                it.session?.sessionId != opened && it.reconnecting == null
            }
            assertPassthroughFor4k(model, chosen)
            // Another upscaling model cannot change a stream on passthrough.
            assertModelChangeKeepsStream(model, other, chosen)
            close(model)

            open(model, fhd!!)
            val state = model.ui.value
            val session = requireNotNull(state.relaySession)
            assertEquals(chosen, session.model)
            val size = requireNotNull(session.sourceSize) { "the 1080p file's size was not read" }
            assertFalse("$size", size.is4kOrHigher())
            assertTrue(state.sessionDescription, state.sessionDescription.startsWith("$chosen · "))
            // The option decides nothing for this file, so no restart.
            onMain { model.setPassthrough4k(false) }
            SystemClock.sleep(2_000)
            assertEquals(session.sessionId, model.ui.value.session?.sessionId)
            assertEquals(null, model.ui.value.reconnecting)
            close(model)

            val fixture = arguments.getString("uhdLocalFixture")
            if (!fixture.isNullOrBlank()) {
                onMain {
                    model.setPassthrough4k(true)
                    model.setQualityTier("hevc-qp18")
                }
                val file = File(context.filesDir, fixture)
                check(file.isFile) { "push $fixture to the debug app's files first" }
                onMain { model.openLocalDocument(Uri.fromFile(file).toString()) }
                awaitPlaying(model, "playback of $fixture") { it.mpvMetrics.positionSeconds > 0.5 }
                assertPassthroughFor4k(model, chosen)
                assertModelChangeKeepsStream(model, other, chosen)
                val local = model.ui.value.session?.sessionId
                onMain { model.setQualityTier("hevc-qp14") }
                awaitPlaying(model, "the local restart for a new quality") {
                    it.session?.sessionId != local && it.reconnecting == null
                }
                assertPassthroughFor4k(model, chosen)
                close(model)
            }
        }
    }

    /** Choosing [other] and then [chosen] again restarts nothing, and both are saved. */
    private fun assertModelChangeKeepsStream(model: RelayViewModel, other: String, chosen: String) {
        val playing = model.ui.value.session?.sessionId
        for (next in listOf(other, chosen)) {
            onMain { model.setModel(next) }
            SystemClock.sleep(2_000)
            assertEquals(next, model.ui.value.selectedModel)
            assertEquals(next, runBlocking { preferences.snapshot() }.model)
            assertEquals("a model change restarted a stream it could not change", playing, model.ui.value.session?.sessionId)
            assertEquals(null, model.ui.value.reconnecting)
        }
    }

    private fun assertPassthroughFor4k(model: RelayViewModel, chosen: String) {
        val state = model.ui.value
        val session = requireNotNull(state.relaySession)
        assertEquals(PASSTHROUGH_MODEL, session.model)
        val size = requireNotNull(session.sourceSize) { "the 4K file's size was not read" }
        assertTrue("$size", size.is4kOrHigher())
        assertTrue(state.sessionDescription, state.sessionDescription.startsWith("passthrough (4K source) · "))
        // The swap is the session's, never the user's choice.
        assertEquals(chosen, state.selectedModel)
        assertEquals(chosen, runBlocking { preferences.snapshot() }.model)
    }

    private fun open(model: RelayViewModel, path: String) {
        onMain { model.openFile(LibraryNode(LibraryNode.Type.FILE, path.substringAfterLast('/'), path)) }
        // A cold model may first build its TensorRT engine on the server.
        awaitPlaying(model, "playback of $path", OPEN_TIMEOUT_MILLIS) { it.mpvMetrics.positionSeconds > 1.0 }
    }

    private fun close(model: RelayViewModel) {
        onMain { model.closePlayback() }
        await("close", model, 60_000) {
            it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
        }
    }

    private fun awaitPlaying(
        model: RelayViewModel,
        label: String,
        timeoutMillis: Long = 90_000,
        ready: (RelayUiState) -> Boolean,
    ) = await(label, model, timeoutMillis) {
        check(it.error == null) { it.error.toString() }
        it.playerState == MpvPlaybackState.PLAYING && !it.busy && ready(it)
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
                "model=${state.relaySession?.model} error=${state.error}",
        )
    }

    private companion object {
        const val SEEK_SECONDS = 300.0
        const val OPEN_TIMEOUT_MILLIS = 300_000L
    }
}
