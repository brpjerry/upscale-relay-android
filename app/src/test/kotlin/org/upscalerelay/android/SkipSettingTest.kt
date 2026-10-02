package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipSettingTest {
    @Test
    fun `default skip is one minute twenty-five`() {
        assertEquals(85, AppPreferences().skipSeconds)
        assertEquals("1:25", formatSkipDuration(DEFAULT_SKIP_SECONDS))
    }

    @Test
    fun `accepts minutes-seconds and plain seconds`() {
        assertEquals(85, parseSkipDuration("1:25"))
        assertEquals(85, parseSkipDuration(" 85 "))
        assertEquals(30, parseSkipDuration("0:30"))
        assertEquals(MAX_SKIP_SECONDS, parseSkipDuration("60:00"))
    }

    @Test
    fun `rejects malformed or out-of-range amounts`() {
        listOf("", "0", "0:00", "1:5", "1:60", "60:01", "-5", "1:2:3", "abc", "1.5").forEach {
            assertNull(it, parseSkipDuration(it))
        }
    }

    @Test
    fun `format round-trips through parse`() {
        listOf(1, 59, 60, 85, 600, MAX_SKIP_SECONDS).forEach {
            assertEquals(it, parseSkipDuration(formatSkipDuration(it)))
        }
    }
}
