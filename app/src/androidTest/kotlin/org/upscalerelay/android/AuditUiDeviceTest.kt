package org.upscalerelay.android

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/**
 * UI regressions for the October 2026 audit, driven through Compose on the
 * debug app. Saved host, port, destination and auto-play are put back.
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

    private fun awaitText(text: String, timeoutMillis: Long = 10_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
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
    }
}
