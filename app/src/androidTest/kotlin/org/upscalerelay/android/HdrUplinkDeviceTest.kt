package org.upscalerelay.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.demux.AndroidMediaSource
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.protocol.ContentLightLevel
import org.upscalerelay.protocol.DolbyVisionConfig
import org.upscalerelay.protocol.MasteringDisplay
import org.upscalerelay.protocol.StaticHdrMetadata
import org.upscalerelay.protocol.VideoColor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local HDR sources: what MediaExtractor reports, what the uplink sends
 * (PROTOCOL.md §3.5, §3.6), and what the relay streams back. Needs the
 * fixtures in the debug app's files directory, `-e hdrFixtures a.mkv,b.mp4`;
 * the relay tests also need the server (`-e auditHost`, default
 * 192.168.0.115). docs/ANDROID_DEVICE_NOTES.md says how the fixtures were made.
 */
@RunWith(AndroidJUnit4::class)
class HdrUplinkDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** What each fixture is, as made from the server's files with ffmpeg. */
    private data class Expected(
        val codec: String,
        val color: VideoColor?,
        val bitDepth: Int?,
        val dolbyVision: DolbyVisionConfig? = null,
        /**
         * The HDR10 static metadata the container declares, which the uplink
         * sends; null where there is none (the test card's is in its SEI).
         */
        val staticHdr: StaticHdrMetadata? = null,
        /**
         * What the server writes into the downlink, per half the frames' SEI
         * where they carry one and else what the uplink sent; null is unchecked.
         */
        val writtenHdr: StaticHdrMetadata? = null,
        /**
         * The downlink under "hdr" to an SDR display: its depth, its transfer
         * (null for a source that names none, which the server tags from the
         * frames), and what the server applied.
         */
        val downlinkDepth: Int,
        val downlinkTransfer: Int?,
        val applied: List<String> = emptyList(),
    )

    private val pq = VideoColor(9, VideoColor.TRANSFER_PQ, 9, "limited")

    private val bt2020Display = MasteringDisplay(
        primaries = listOf(34_000 to 16_000, 13_250 to 34_500, 7_500 to 3_000),
        whitePoint = 15_635 to 16_450,
        maxLuminance = 100_000_000,
        minLuminance = 50,
    )

    /** hdr10-container's container: a 10000-nit BT.2020 mastering display, MaxCLL 10000, MaxFALL 400. */
    private val containerHdr = StaticHdrMetadata(bt2020Display, ContentLightLevel(10_000, 400))

    /** Its HEVC SEI, which differs: a 1000-nit display, MaxCLL 1000, MaxFALL 400. */
    private val seiHdr = StaticHdrMetadata(bt2020Display.copy(maxLuminance = 10_000_000), ContentLightLevel(1_000, 400))
    private val expected = mapOf(
        "hdr10-card.mkv" to Expected("hevc", pq, 10, downlinkDepth = 10, downlinkTransfer = 16),
        // MediaExtractor drops Matroska's mastering display; MatroskaHdrStatic
        // reads it. The server prefers the frames' SEI to it.
        "hdr10-container.mkv" to Expected(
            "hevc", pq, 10, staticHdr = containerHdr, writtenHdr = seiHdr, downlinkDepth = 10, downlinkTransfer = 16,
        ),
        // MediaExtractor reports nothing of an MP4's mdcv and clli; Mp4HdrStatic reads them.
        "hdr10-container.mp4" to Expected(
            "hevc", pq, 10, staticHdr = containerHdr, writtenHdr = seiHdr, downlinkDepth = 10, downlinkTransfer = 16,
        ),
        // The same with the SEI stripped from its samples and hvcC: only the boxes say it.
        "hdr10-boxes-only.mp4" to Expected(
            "hevc", pq, 10, staticHdr = containerHdr, writtenHdr = containerHdr, downlinkDepth = 10, downlinkTransfer = 16,
        ),
        "hlg.mkv" to Expected(
            "hevc", VideoColor(9, VideoColor.TRANSFER_HLG, 9, "limited"), 10, downlinkDepth = 10, downlinkTransfer = 18,
        ),
        "dv81.mkv" to Expected(
            "hevc", pq, 10, DolbyVisionConfig(8, 6, 1),
            downlinkDepth = 10, downlinkTransfer = 1, applied = listOf("dolby_vision"),
        ),
        "dv81.mp4" to Expected(
            "hevc", pq, 10, DolbyVisionConfig(8, 6, 1),
            downlinkDepth = 10, downlinkTransfer = 1, applied = listOf("dolby_vision"),
        ),
        "sdr10-hevc.mkv" to Expected("hevc", null, 10, downlinkDepth = 8, downlinkTransfer = null),
        // High 10 allows 8 bits too, and MediaExtractor reports no bits-per-sample for H.264.
        "sdr10-h264.mkv" to Expected("h264", null, null, downlinkDepth = 8, downlinkTransfer = null),
    )

    private fun fixtures(): List<File> {
        val names = InstrumentationRegistry.getArguments().getString("hdrFixtures")
        assumeTrue("pass -e hdrFixtures <files in app files>", !names.isNullOrBlank())
        return names!!.split(',').map { File(instrumentation.targetContext.filesDir, it.trim()) }
            .onEach { check(it.isFile) { "missing fixture ${it.name}" } }
    }

    @Test
    fun localSourcesDescribeTheirHdr() {
        for (file in fixtures()) {
            logFormats(file)
            val info = AndroidMediaSource.open(instrumentation.targetContext, Uri.fromFile(file)).use { it.videoInfo }
            Log.i(TAG, "${file.name}: codec=${info.codec} color=${info.color} depth=${info.bitDepth} " +
                "hdr=${info.staticHdr} dolbyVision=${info.dolbyVision}")
            val want = expected[file.name] ?: continue
            assertEquals(file.name, want.codec, info.codec)
            assertEquals(file.name, want.color, info.color)
            assertEquals(file.name, want.bitDepth, info.bitDepth)
            assertEquals(file.name, want.dolbyVision, info.dolbyVision)
            assertEquals(file.name, want.staticHdr, info.staticHdr)
        }
    }

    /**
     * An MP4 whose edit list trims its start has samples before zero
     * (dv81.mp4, an ffmpeg cut, starts at -576 ms). Reading a negative sample
     * time as the end ended it before its first frame. Pass it as
     * `-e editListFixture`.
     */
    @Test
    fun anMp4StartingBeforeZeroPlaysInSync() {
        val name = InstrumentationRegistry.getArguments().getString("editListFixture")
        assumeTrue("pass -e editListFixture <an MP4 with negative sample times>", !name.isNullOrBlank())
        val file = File(instrumentation.targetContext.filesDir, name!!).also { check(it.isFile) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            prepare(model, tenBitVideo = false)
            play(model, file)
            await("${file.name} past 5 s with audio", model) {
                it.mpvMetrics.positionSeconds > 5 && it.mpvMetrics.audioPtsSeconds > 5
            }
            val metrics = model.ui.value.mpvMetrics
            Log.i(TAG, "${file.name} at ${metrics.positionSeconds}: audio ${metrics.audioPtsSeconds}, " +
                "avsync ${metrics.avSyncSeconds}, drops ${metrics.outputDroppedFrames}/${metrics.decoderDroppedFrames}")
            check(kotlin.math.abs(metrics.avSyncSeconds) < 0.1) { "A/V apart by ${metrics.avSyncSeconds} s" }
            close(model)
        }
    }

    @Test
    fun localHdrStreamsAsTheServerShouldSendIt() {
        val files = fixtures()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            prepare(model, tenBitVideo = false)
            for (file in files) {
                val want = expected[file.name] ?: continue
                play(model, file)
                val session = requireNotNull(model.ui.value.session)
                Log.i(TAG, "${file.name} downlink: ${session.pictureDiagnostics()} " +
                    "color=${session.downlinkColor} mapping=${session.downlinkMapping}")
                assertEquals(file.name, want.downlinkDepth, session.downlinkBitDepth)
                want.downlinkTransfer?.let { assertEquals(file.name, it, session.downlinkColor?.transfer) }
                assertEquals(file.name, want.applied, session.downlinkMapping?.applied.orEmpty())
                want.writtenHdr?.let { hdr ->
                    val written = writtenStaticHdr(model, session.sessionId)
                    Log.i(TAG, "${file.name} static metadata written: $written")
                    assertEquals(file.name, hdr.toJson(), written)
                }
                close(model)
            }
        }
    }

    @Test
    fun tenBitVideoWidensAnSdrSourceAndTurningItOffRestarts() {
        val file = fixtures().first()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            prepare(model, tenBitVideo = true)
            try {
                play(model, file)
                assertEquals("${file.name} under \"all\"", 10, model.ui.value.session?.downlinkBitDepth)
                val sessionId = model.ui.value.session?.sessionId
                onMain { model.setTenBitVideo(false) }
                await("8-bit restart", model, 90_000) {
                    it.session?.sessionId != sessionId && it.playerState == MpvPlaybackState.PLAYING && it.reconnecting == null
                }
                assertEquals("${file.name} under \"hdr\"", 8, model.ui.value.session?.downlinkBitDepth)
                close(model)
            } finally {
                // A failure before the switch must not leave it on for later runs.
                onMain { model.setTenBitVideo(false) }
            }
        }
    }

    private fun prepare(model: RelayViewModel, tenBitVideo: Boolean) {
        await("preferences", model) { it.preferencesLoaded }
        onMain {
            model.setHost(InstrumentationRegistry.getArguments().getString("auditHost") ?: "192.168.0.115")
            model.setAutoPlayNext(false)
            model.setModel("passthrough")
            model.setQualityTier("hevc-qp4")
            model.setTenBitVideo(tenBitVideo)
            // What `expected` says comes back is for an SDR display; on an HDR
            // panel the app would otherwise name pq (HdrOutputDeviceTest).
            model.setHdrOutput(false)
        }
    }

    private fun play(model: RelayViewModel, file: File) {
        onMain { model.openLocalDocument(Uri.fromFile(file).toString()) }
        await("${file.name} playing", model, 90_000) {
            it.playerState == MpvPlaybackState.PLAYING && it.mpvMetrics.positionSeconds > 0.5 && !it.busy
        }
    }

    private fun close(model: RelayViewModel) {
        onMain { model.closePlayback() }
        await("closed", model, 60_000) {
            it.playingPath == null && !it.busy && it.sessionState == SessionState.BROWSING
        }
    }

    /**
     * The static metadata the server writes into session [sessionId]'s
     * downlink: GET /status sessions[].pipeline.hdr_metadata.static.
     */
    private fun writtenStaticHdr(model: RelayViewModel, sessionId: String): JsonObject? {
        val ui = model.ui.value
        // As the app connects: trimmed host (an IPv6 literal is bracketed here), default port.
        val connection = URL("http", ui.host.trim(), ui.port.toIntOrNull() ?: 8590, "/status")
            .openConnection() as HttpURLConnection
        val status = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally {
            connection.disconnect()
        }
        val entry = status.getValue("sessions").jsonArray.map { it.jsonObject }
            .single { it["id"]?.jsonPrimitive?.content == sessionId }
        return (entry["pipeline"] as? JsonObject)?.let { it["hdr_metadata"] as? JsonObject }?.let { it["static"] as? JsonObject }
    }

    private fun logFormats(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                Log.i(TAG, "${file.name} track $index: $format")
                for (key in listOf("csd-2", MediaFormat.KEY_HDR_STATIC_INFO)) {
                    val buffer = (if (format.containsKey(key)) format.getByteBuffer(key) else null)?.duplicate() ?: continue
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    Log.i(TAG, "${file.name} track $index $key: " + bytes.joinToString("") { "%02x".format(it) })
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun model(scenario: ActivityScenario<MainActivity>): RelayViewModel {
        check(instrumentation.targetContext.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the device tests"
        }
        lateinit var model: RelayViewModel
        scenario.onActivity { model = ViewModelProvider(it)[RelayViewModel::class.java] }
        return model
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(
        label: String,
        model: RelayViewModel,
        timeoutMillis: Long = 45_000,
        ready: (RelayUiState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.ui.value
            check(state.error == null) { "$label: ${state.error}" }
            if (ready(state)) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label; state=${model.ui.value.sessionState}, player=${model.ui.value.playerState}")
    }

    private companion object {
        const val TAG = "HdrUplinkDeviceTest"
    }
}
