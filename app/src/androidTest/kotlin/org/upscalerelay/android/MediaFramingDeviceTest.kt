package org.upscalerelay.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.MediaPacket
import java.io.ByteArrayInputStream
import java.io.EOFException

@RunWith(AndroidJUnit4::class)
class MediaFramingDeviceTest {
    @Test
    fun truncatedMediaPayloadFailsPromptly() {
        val complete = MediaFraming.encode(MediaPacket(byteArrayOf(1, 2, 3, 4)))
        val truncated = complete.copyOf(MediaFraming.HEADER_LENGTH + 2)

        val failure = runCatching {
            MediaFraming.read(ByteArrayInputStream(truncated))
        }.exceptionOrNull()

        assertTrue("expected EOFException, got $failure", failure is EOFException)
    }
}
