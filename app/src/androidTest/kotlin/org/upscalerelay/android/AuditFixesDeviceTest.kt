package org.upscalerelay.android

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.SessionState
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.protocol.LibraryNode
import java.io.File
import java.time.Instant

/**
 * Regressions for the October 2026 audit fixes. Runs only in the co-installed
 * debug app, whose data is disposable; never touches the release app.
 */
@RunWith(AndroidJUnit4::class)
class AuditFixesDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences by lazy { AppPreferencesStore(context) }

    @Test
    fun savedDiagnosticLoggingIsRestoredAtLaunch() {
        requireDebugPackage()
        runBlocking { preferences.setFileLoggingEnabled(true) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = model(scenario)
                await("logging restored at launch", model) {
                    it.preferencesLoaded && it.fileLoggingEnabled && it.logFileName != null
                }
                assertTrue(AppLog.active)
                onMain { model.setFileLoggingEnabled(false) }
                await("logging stopped", model) { !it.fileLoggingEnabled && it.logFileName == null }
            }
        } finally {
            runBlocking { preferences.setFileLoggingEnabled(false) }
        }
    }

    @Test
    fun importedDiagnosticLoggingPreferenceTakesEffect() {
        requireDebugPackage()
        runBlocking { preferences.setFileLoggingEnabled(false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = model(scenario)
                await("preferences", model) { it.preferencesLoaded && !it.fileLoggingEnabled }
                val current = runBlocking { preferences.snapshot() }
                onMain { model.importData(backup("enable", current.copy(fileLoggingEnabled = true))) }
                await("import enables logging", model) {
                    it.fileLoggingEnabled && it.logFileName != null && it.backupStatus?.failed == false
                }
                assertTrue(AppLog.active)
                onMain {
                    model.dismissBackupStatus()
                    model.importData(backup("disable", current.copy(fileLoggingEnabled = false)))
                }
                await("import disables logging", model) {
                    !it.fileLoggingEnabled && it.logFileName == null && it.backupStatus?.failed == false
                }
                assertFalse(AppLog.active)
            }
        } finally {
            runBlocking { preferences.setFileLoggingEnabled(false) }
        }
    }

    @Test
    fun firstOpenFallbackSavesProgressAndEndsAtEof() {
        requireDebugPackage()
        val (uri, key) = localClip()
        val relay = FakeRelay()
        withFakeRelay(relay) { model ->
            openRejectedLocal(model, relay, uri)
            onMain { model.playLocalFallback() }
            await("direct playback", model, 30_000) {
                it.directLocalFallback && it.playerState == MpvPlaybackState.PLAYING
            }
            await("progress saved during direct playback", model, 40_000) {
                (it.playbackProgress[key]?.positionSeconds ?: 0.0) >= 10.0
            }
            onMain { model.seekTo(147.0) }
            // Auto-play is off: a natural end returns to the library.
            await("natural EOF returns to the library", model, 30_000) { it.playingPath == null }
            // Saved asynchronously, so wait for it rather than read it once.
            await("EOF pins the history entry at 100%", model) {
                val saved = it.playbackProgress[key]
                saved != null && saved.durationSeconds > 0 &&
                    kotlin.math.abs(saved.durationSeconds - saved.positionSeconds) < 0.5
            }
        }
    }

    @Test
    fun firstOpenFallbackResumesAtSavedPosition() {
        requireDebugPackage()
        val (uri, key) = localClip()
        runBlocking { preferences.setPlaybackPosition(key, 40.0, 150.0) }
        val relay = FakeRelay()
        withFakeRelay(relay) { model ->
            openRejectedLocal(model, relay, uri)
            onMain { model.playLocalFallback() }
            await("direct playback resumes at the saved position", model, 30_000) {
                it.directLocalFallback && it.playerState == MpvPlaybackState.PLAYING &&
                    it.mpvMetrics.positionSeconds >= 39.0
            }
            onMain { model.closePlayback() }
            await("close", model, 30_000) { it.playingPath == null && !it.busy }
        }
    }

    /** The generated 150 s fixture; push it with `run-as ... cp` into files/. */
    private fun localClip(): Pair<String, String> {
        val file = File(context.filesDir, "audit-clip-150s.mp4")
        assumeTrue("push audit-clip-150s.mp4 into the debug app's files directory", file.isFile)
        val uri = Uri.fromFile(file).toString()
        val key = "local:$uri"
        runBlocking { preferences.clearPlaybackPosition(key) }
        return uri to key
    }

    @Test
    fun upWhileAChildDirectoryLoadsIsNotUndone() {
        requireDebugPackage()
        val relay = FakeRelay(
            library = mapOf(
                "" to listOf(FakeEntry("A", directory = true)),
                "A" to listOf(FakeEntry("B", directory = true), FakeEntry("a1.mkv")),
                "A/B" to listOf(FakeEntry("b1.mkv")),
            ),
            libraryDelayMillis = { path -> if (path == "A/B") 2_500 else 0 },
        )
        withFakeRelay(relay) { model ->
            openChild(model, "A")
            onMain { model.openDirectory(child(model, "B")) }
            await("child load in flight", model) { it.libraryLoading }
            onMain { model.upDirectory() }
            assertEquals("", model.ui.value.currentDirectory?.path)
            await("child load finished", model, 15_000) { !it.libraryLoading }
            assertEquals("Up must stand", "", model.ui.value.currentDirectory?.path)
            assertTrue(model.ui.value.directoryStack.isEmpty())
            // Navigation still works from where the user went.
            openChild(model, "A")
            assertEquals(listOf(""), model.ui.value.directoryStack.map { it.path })
        }
    }

    private fun openChild(model: RelayViewModel, name: String) {
        val target = child(model, name)
        onMain { model.openDirectory(target) }
        await("open $name", model) { !it.libraryLoading && it.currentDirectory?.path == target.path }
    }

    private fun child(model: RelayViewModel, name: String): LibraryNode =
        requireNotNull(model.ui.value.currentDirectory?.children?.firstOrNull { it.name == name }) {
            "no $name in ${model.ui.value.currentDirectory?.path}"
        }

    /**
     * Points a fresh Activity at [relay] and connects. The debug app's saved
     * host, port, auto-play and sort are put back afterwards.
     */
    private fun withFakeRelay(
        relay: FakeRelay,
        sort: LibrarySort? = null,
        body: (RelayViewModel) -> Unit,
    ) {
        val original = runBlocking { preferences.snapshot() }
        if (sort != null) runBlocking { preferences.setLibrarySort(sort.name) }
        try {
            relay.use {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val model = model(scenario)
                    await("preferences", model) { it.preferencesLoaded }
                    onMain {
                        model.setHost("127.0.0.1")
                        model.setPort(relay.port.toString())
                        model.setAutoPlayNext(false)
                        model.connect()
                    }
                    await("connect to the fake relay", model) {
                        it.sessionState == SessionState.BROWSING && !it.busy &&
                            it.capabilities?.serverName == "audit-fake-relay" && !it.libraryLoading
                    }
                    body(model)
                }
            }
        } finally {
            runBlocking {
                preferences.setHost(original.host)
                preferences.setPort(original.port)
                preferences.setAutoPlayNext(original.autoPlayNext)
                preferences.setLibrarySort(original.librarySort)
            }
        }
    }

    /** The relay refuses the session after the document bridge is already open. */
    private fun openRejectedLocal(model: RelayViewModel, relay: FakeRelay, uri: String) {
        onMain { model.openLocalDocument(uri) }
        await("relay rejection with the original still offered", model, 30_000) {
            !it.busy && it.error != null && it.playingPath != null && it.localPlayback
        }
        assertEquals(1, relay.openRequests.get())
    }

    private fun backup(name: String, value: AppPreferences): Uri {
        val file = File(context.cacheDir, "audit-backup-$name.json")
        file.writeText(BackupCodec.encode(value, "audit-test", Instant.now()))
        return Uri.fromFile(file)
    }

    private fun requireDebugPackage() {
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the audit tests"
        }
    }

    private fun model(scenario: ActivityScenario<MainActivity>): RelayViewModel {
        lateinit var model: RelayViewModel
        scenario.onActivity { model = ViewModelProvider(it)[RelayViewModel::class.java] }
        return model
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
            SystemClock.sleep(100)
        }
        val state = model.ui.value
        error(
            "Timed out: $label; session=${state.sessionState} player=${state.playerState} " +
                "playing=${state.playingPath} busy=${state.busy} error=${state.error} " +
                "logging=${state.fileLoggingEnabled}/${state.logFileName} " +
                "fallback=${state.directLocalFallback} position=${state.mpvMetrics.positionSeconds} " +
                "directory=${state.currentDirectory?.path}/${state.currentDirectory?.children?.size} " +
                "stack=${state.directoryStack.map { it.path }} loading=${state.libraryLoading}",
        )
    }
}
