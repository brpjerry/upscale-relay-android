package org.upscalerelay.android

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
                "logging=${state.fileLoggingEnabled}/${state.logFileName}",
        )
    }
}
