package org.upscalerelay.android

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Launches the player's Activity on the shared tablet, which is PIN-locked
 * when nobody is at it. A sleeping tablet keeps the keyguard's portrait and
 * the player never gets its landscape surface, so this wakes it and shows the
 * Activity over the keyguard (test only; PiP cannot be entered over it).
 */
internal fun launchOverKeyguard(): ActivityScenario<MainActivity> {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent KEYCODE_WAKEUP"))
        .use { it.readBytes() }
    return ActivityScenario.launch(MainActivity::class.java).also { scenario ->
        scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
        }
    }
}
