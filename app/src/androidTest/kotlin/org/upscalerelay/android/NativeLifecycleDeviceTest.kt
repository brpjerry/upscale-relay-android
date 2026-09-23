package org.upscalerelay.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.player.mpv.MpvPlaybackState
import org.upscalerelay.player.mpv.MpvPlayerEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exercises the real process-global JNI owner without a relay server or media. */
@RunWith(AndroidJUnit4::class)
class NativeLifecycleDeviceTest {
    @Test
    fun replacementWaitsForPreviousNativeOwnerToClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug")) {
            "Build with -PcoinstallDebug=true before running the audit tests"
        }
        val first = MpvPlayerEngine(context)
        val replacement = MpvPlayerEngine(context)
        val worker = Executors.newSingleThreadExecutor()
        try {
            first.initialize()
            assertEquals(MpvPlaybackState.IDLE, first.state.value)
            val attempted = CountDownLatch(1)
            val initializing = worker.submit {
                attempted.countDown()
                replacement.initialize()
            }
            assertTrue("replacement initialization should start", attempted.await(5, TimeUnit.SECONDS))
            try {
                initializing.get(500, TimeUnit.MILLISECONDS)
                throw AssertionError("replacement initialized while the previous owner was alive")
            } catch (_: TimeoutException) {
                // Only the previous owner's close can release the native slot.
            }
            assertEquals(MpvPlaybackState.CREATED, replacement.state.value)
            first.close()
            initializing.get(20, TimeUnit.SECONDS)
            assertEquals(MpvPlaybackState.IDLE, replacement.state.value)
        } finally {
            // Release the first permit before joining the blocked initializer,
            // even when an assertion fails.
            try {
                first.close()
            } finally {
                replacement.close()
                worker.shutdownNow()
                worker.awaitTermination(20, TimeUnit.SECONDS)
            }
        }
    }
}
