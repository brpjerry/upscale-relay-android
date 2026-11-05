package org.upscalerelay.android

import android.app.PictureInPictureParams
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import `is`.xyz.mpv.MPVLib
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.player.mpv.VideoOutput
import java.io.File

/**
 * HDR output (docs/HDR_OUTPUT.md) on a device with an HDR display, against the
 * real relay on `passthrough`, `hevc-qp4`, cover:
 *
 * - an HDR server file plays as PQ through the relay layer, the panel goes to
 *   HDR (`mIsHdrLayerPresent`), with no drops, subtitles at the display's peak;
 * - an SDR file plays exactly as before;
 * - the battery saver and "Show HDR videos in HDR" send an HDR video back to
 *   SDR at its position, and back again;
 * - picture-in-picture keeps PQ, without a reload;
 * - a Dolby Vision source the server maps is remapped for each display.
 *
 * `-e hdrPath '<server file, HDR10>' -e sdrPath '<server file, SDR>'
 *  [-e mappedHdrPath '<Dolby Vision 8.1 server file>'] [-e auditHost 192.168.0.115]`
 *
 * Each run writes what SurfaceFlinger and DisplayManager showed to
 * `files/hdr-output-<test>.txt` in the debug app.
 */
@RunWith(AndroidJUnit4::class)
class HdrOutputDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val record = StringBuilder()

    private fun path(name: String): String {
        val value = arguments.getString(name)
        assumeTrue("pass -e $name <server file>", !value.isNullOrBlank())
        return value!!
    }

    @Test
    fun anHdrVideoPlaysAsHdrOnTheHdrPanel() = player("hdr") { model ->
        val peak = hdrDisplayPeak(model)
        open(model, path("hdrPath"))
        val output = awaitOutput(model, VideoOutput.Pq(peak))
        checkPqOptions(peak)
        SystemClock.sleep(3_000)
        assertTrue("an HDR layer on the panel", hdrLayerPresent("playing"))
        assertTrue("the relay layer tagged BT2020_PQ", relayLayerIsPq("playing"))
        assertNoDrops(model, "steady", 20_000)

        // Each relay seek is a load: it decides again, and stays PQ.
        val target = (model.ui.value.mpvMetrics.durationSeconds / 2).coerceAtLeast(5.0)
        val epoch = epochOf(model)
        onMain { model.seekTo(target) }
        awaitPlaying(model, "seek to $target") { epochOf(model) > epoch }
        assertEquals(output, model.ui.value.videoOutput)
        SystemClock.sleep(3_000)
        assertTrue("an HDR layer after the seek", hdrLayerPresent("seeked"))
        assertNoDrops(model, "after the seek", 8_000)
    }

    @Test
    fun anSdrVideoPlaysAsBefore() = player("sdr") { model ->
        open(model, path("sdrPath"))
        awaitOutput(model, VideoOutput.Sdr)
        assertEquals("gpu", MPVLib.getPropertyString("vo"))
        assertEquals("auto", MPVLib.getPropertyString("target-trc"))
        SystemClock.sleep(3_000)
        assertFalse("no relay layer", relayLayerPresent("playing"))
        assertFalse("no HDR layer", hdrLayerPresent("playing"))
        assertNoDrops(model, "steady", 15_000)
    }

    @Test
    fun theBatterySaverAndTheSettingSendHdrBackToSdr() = player("fallback") { model ->
        val peak = hdrDisplayPeak(model)
        open(model, path("hdrPath"))
        awaitOutput(model, VideoOutput.Pq(peak))
        val power = context.getSystemService(PowerManager::class.java)
        try {
            // Battery saver is refused while charging: unplug, as far as the
            // battery service knows.
            shell("cmd battery unplug")
            shell("cmd power set-mode 1")
            awaitCondition("battery saver on", 10_000) { power.isPowerSaveMode }
            reloadedTo(model, VideoOutput.Sdr, "battery saver on")
            assertFalse("no HDR layer with the battery saver on", hdrLayerPresent("saver"))
            assertNoDrops(model, "battery saver on", 8_000)

            shell("cmd power set-mode 0")
            awaitCondition("battery saver off", 10_000) { !power.isPowerSaveMode }
            reloadedTo(model, VideoOutput.Pq(peak), "battery saver off")
            assertTrue("an HDR layer again", hdrLayerPresent("saver-off"))
        } finally {
            shell("cmd power set-mode 0")
            shell("cmd battery reset")
        }
        try {
            onMain { model.setHdrOutput(false) }
            reloadedTo(model, VideoOutput.Sdr, "setting off")
            assertFalse("no HDR layer with the setting off", hdrLayerPresent("setting-off"))
            onMain { model.setHdrOutput(true) }
            reloadedTo(model, VideoOutput.Pq(peak), "setting on")
            assertTrue("an HDR layer with the setting on", hdrLayerPresent("setting-on"))
        } finally {
            onMain { model.setHdrOutput(true) }
        }
    }

    @Test
    fun pictureInPictureStaysHdr() = player("pip") { model ->
        val peak = hdrDisplayPeak(model)
        open(model, path("hdrPath"))
        awaitOutput(model, VideoOutput.Pq(peak))
        SystemClock.sleep(3_000)
        val epoch = epochOf(model)
        val before = model.ui.value.mpvMetrics
        scenario.onActivity { it.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
        SystemClock.sleep(15_000)
        val after = model.ui.value
        log(
            "pip drops=${after.mpvMetrics.outputDroppedFrames - before.outputDroppedFrames}/" +
                "${after.mpvMetrics.decoderDroppedFrames - before.decoderDroppedFrames} " +
                "avsync=${after.mpvMetrics.avSyncSeconds} output=${after.videoOutput.describe()}",
        )
        // The panel drops its boost for the small window, but the PQ layer
        // plays on; a switch would cost a reload as the window shrinks.
        assertEquals(VideoOutput.Pq(peak), after.videoOutput)
        assertEquals("no reload in PiP", epoch, epochOf(model))
        // The layer keeps mpv's full-size buffer, scaled into the window:
        // its HWC line shows the whole source crop in a small display frame.
        assertTrue("the relay layer stays", relayLayerPresent("pip"))
        hdrLayerPresent("pip")
        assertEquals(0L, after.mpvMetrics.outputDroppedFrames - before.outputDroppedFrames)
        assertEquals(0L, after.mpvMetrics.decoderDroppedFrames - before.decoderDroppedFrames)
    }

    @Test
    fun aMappedSourceIsRemappedForEachDisplay() = player("mapped") { model ->
        val peak = hdrDisplayPeak(model)
        open(model, path("mappedHdrPath"))
        awaitOutput(model, VideoOutput.Pq(peak))
        // Named pq, a profile 8.1 source streams its HDR10 base.
        assertEquals("pq", model.ui.value.session?.downlinkMapping?.transfer)
        val power = context.getSystemService(PowerManager::class.java)
        try {
            shell("cmd battery unplug")
            shell("cmd power set-mode 1")
            awaitCondition("battery saver on", 10_000) { power.isPowerSaveMode }
            reloadedTo(model, VideoOutput.Sdr, "battery saver on")
            // The reload's seek named sdr: the server applied the RPU.
            val mapping = model.ui.value.session?.downlinkMapping
            assertEquals("sdr", mapping?.transfer)
            assertEquals(listOf("dolby_vision"), mapping?.applied)
            assertFalse(model.ui.value.session?.downlinkColor?.isHdr == true)
        } finally {
            shell("cmd power set-mode 0")
            shell("cmd battery reset")
        }
        reloadedTo(model, VideoOutput.Pq(peak), "battery saver off")
        assertEquals("pq", model.ui.value.session?.downlinkMapping?.transfer)
    }

    // ---- helpers -----------------------------------------------------------

    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun player(label: String, body: (RelayViewModel) -> Unit) {
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the device tests"
        }
        // A sleeping tablet would keep the keyguard's portrait, and the
        // player would never get its landscape surface.
        shell("input keyevent KEYCODE_WAKEUP")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { launched ->
                scenario = launched
                lateinit var model: RelayViewModel
                launched.onActivity {
                    // The shared tablet is PIN-locked when nobody is at it
                    // (test only; PiP cannot be entered over the keyguard).
                    it.setShowWhenLocked(true)
                    it.setTurnScreenOn(true)
                    model = ViewModelProvider(it)[RelayViewModel::class.java]
                }
                await("preferences", model) { it.preferencesLoaded }
                onMain {
                    model.setHost(arguments.getString("auditHost") ?: "192.168.0.115")
                    model.setPort(arguments.getString("auditPort") ?: "8590")
                    model.setAutoPlayNext(false)
                    model.setModel("passthrough")
                    model.setQualityTier("hevc-qp4")
                    model.setFitMode("cover")
                    model.setHdrOutput(true)
                    model.connect()
                }
                await("connect", model, 30_000) {
                    it.sessionState == SessionState.BROWSING && !it.busy && it.capabilities != null
                }
                try {
                    body(model)
                } finally {
                    onMain { model.closePlayback() }
                    await("closed", model, 60_000) { it.playingPath == null && !it.busy }
                }
            }
        } finally {
            File(context.filesDir, "hdr-output-$label.txt").writeText(record.toString())
        }
    }

    private fun hdrDisplayPeak(model: RelayViewModel): Double {
        assumeTrue("HDR output needs API 33", Build.VERSION.SDK_INT >= HDR_OUTPUT_MIN_SDK)
        val display = model.ui.value.displayHdr
        log("display $display powerSave=${model.ui.value.powerSave}")
        assumeTrue("needs a display that shows HDR10 or HLG", display.hdr)
        assumeTrue("needs the battery saver off", !model.ui.value.powerSave)
        return checkNotNull(display.peakNits) { "the display reports no peak" }
    }

    private fun open(model: RelayViewModel, path: String) {
        log("open $path")
        onMain { model.openRecent(path) }
        awaitPlaying(model, "open", 120_000) { it.session != null }
    }

    private fun awaitOutput(model: RelayViewModel, output: VideoOutput): VideoOutput {
        await("output ${output.describe()}", model, 20_000) { it.videoOutput == output }
        log("output ${output.describe()} epoch=${epochOf(model)} picture=${model.ui.value.session?.pictureDiagnostics()}")
        return output
    }

    /** A reload at the current position that ends on [output]. */
    private fun reloadedTo(model: RelayViewModel, output: VideoOutput, label: String) {
        val epoch = epochOf(model)
        val position = model.ui.value.mpvMetrics.positionSeconds
        val started = SystemClock.elapsedRealtime()
        awaitPlaying(model, label, 60_000) { epochOf(model) > epoch && it.videoOutput == output }
        val state = model.ui.value
        log(
            "$label: reloaded to ${output.describe()} in ${SystemClock.elapsedRealtime() - started} ms, " +
                "epoch $epoch -> ${epochOf(model)}, position %.1f -> %.1f, picture=${state.session?.pictureDiagnostics()}"
                    .format(position, state.mpvMetrics.positionSeconds),
        )
        // At the position it was, not from the start.
        assertTrue("$label resumed near %.1f".format(position), state.mpvMetrics.positionSeconds >= position - 5)
    }

    private fun checkPqOptions(peak: Double) {
        val nits = Math.round(peak).toString()
        val options = listOf("vo", "egl-output-format", "target-trc", "target-prim", "target-peak", "sub-hdr-peak", "image-subs-hdr-peak")
            .associateWith { MPVLib.getPropertyString(it) }
        log("mpv $options")
        assertEquals("gpu-next", options["vo"])
        assertEquals("rgb10_a2", options["egl-output-format"])
        assertEquals("pq", options["target-trc"])
        assertEquals("bt.2020", options["target-prim"])
        assertEquals(nits, options["target-peak"]?.substringBefore('.'))
        // Subtitles at the interface's white, which the panel boosts with the picture.
        assertEquals(nits, options["sub-hdr-peak"]?.substringBefore('.'))
        assertEquals(nits, options["image-subs-hdr-peak"]?.substringBefore('.'))
    }

    private fun assertNoDrops(model: RelayViewModel, label: String, millis: Long) {
        val before = model.ui.value.mpvMetrics
        SystemClock.sleep(millis)
        val after = model.ui.value.mpvMetrics
        val output = after.outputDroppedFrames - before.outputDroppedFrames
        val decoder = after.decoderDroppedFrames - before.decoderDroppedFrames
        log("$label ${millis / 1000}s drops=$output/$decoder avsync=${after.avSyncSeconds} cache=${after.cacheDurationMillis}ms")
        assertEquals("$label output drops", 0L, output)
        assertEquals("$label decoder drops", 0L, decoder)
    }

    private fun epochOf(model: RelayViewModel) = model.ui.value.session?.epoch ?: -1

    /** DisplayManager's own word on whether the panel is in HDR. */
    private fun hdrLayerPresent(tag: String): Boolean {
        val line = shell("dumpsys display").lines().firstOrNull { "mIsHdrLayerPresent" in it }?.trim().orEmpty()
        log("display[$tag] $line")
        return line.endsWith("=true")
    }

    /**
     * The relay layer as SurfaceFlinger composes it: its line in the HWC
     * table (format `0x2b` is RGBA_1010102, then the composition type) and
     * its `LayerFE` block, which holds the dataspace. Empty when there is
     * none.
     */
    private fun surfaceFlinger(tag: String): List<String> {
        val dump = shell("dumpsys SurfaceFlinger")
        File(context.filesDir, "hdr-output-sf-$tag.txt").writeText(dump)
        val lines = dump.lines()
        val table = lines.filter { RELAY_LAYER in it && " | " in it }
        val frontEnd = lines.indexOfFirst { "+ LayerFE ($RELAY_LAYER" in it }
        val block = if (frontEnd < 0) emptyList() else lines.subList(frontEnd, minOf(lines.size, frontEnd + 12))
            .filter { "composition type" in it || "dataspace=" in it }
        return (table + block).map { it.trim() }.onEach { log("sf[$tag] ${it.take(240)}") }
    }

    private fun relayLayerPresent(tag: String) = surfaceFlinger(tag).isNotEmpty()

    private fun relayLayerIsPq(tag: String) = surfaceFlinger(tag).any { "dataspace=BT2020_PQ" in it }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        record.append(line).append('\n')
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    /** Until the picture moves (and [ready]), failing on any error. */
    private fun awaitPlaying(
        model: RelayViewModel,
        label: String,
        timeoutMillis: Long = 60_000,
        ready: (RelayUiState) -> Boolean = { true },
    ) {
        var last = -1.0
        await(label, model, timeoutMillis) { state ->
            val position = state.mpvMetrics.positionSeconds
            val moving = state.playerState == MpvPlaybackState.PLAYING && !state.busy && !state.seeking &&
                last >= 0 && position > last
            last = if (state.playerState == MpvPlaybackState.PLAYING) position else -1.0
            moving && ready(state)
        }
    }

    private fun awaitCondition(label: String, timeoutMillis: Long, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready()) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }

    private fun await(label: String, model: RelayViewModel, timeoutMillis: Long = 20_000, ready: (RelayUiState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.ui.value
            check(state.error == null) { "$label: ${state.error}" }
            if (ready(state)) return
            SystemClock.sleep(100)
        }
        val state = model.ui.value
        error(
            "Timed out: $label; session=${state.sessionState} player=${state.playerState} " +
                "output=${state.videoOutput.describe()} epoch=${state.session?.epoch}",
        )
    }

    private companion object {
        const val TAG = "HdrOutputDeviceTest"
        const val RELAY_LAYER = "relay-hdr-video"
    }
}
