package org.upscalerelay.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.demux.AndroidMediaSource
import java.io.File

/** Produces a host-comparable timeline for the exact SAF document used by Phase 4 testing. */
@RunWith(AndroidJUnit4::class)
class Phase4PtsDeviceTest {
    @Test
    fun dumpPersistedTheClubTimeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val uriValue = InstrumentationRegistry.getArguments().getString("phase4Uri")
        assertTrue("pass -e phase4Uri <document URI>", !uriValue.isNullOrBlank())

        val source = AndroidMediaSource.open(context, Uri.parse(requireNotNull(uriValue)))
        val output = File(instrumentation.targetContext.filesDir, "phase4-pts.csv")
        output.parentFile?.mkdirs()
        var count = 0
        output.bufferedWriter().use { writer ->
            writer.appendLine("pts_us,keyframe")
            source.openPacketReader(null).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    writer.append(packet.pts.toString())
                    writer.append(',')
                    writer.appendLine(if (packet.keyframe) "1" else "0")
                    count += 1
                }
            }
        }
        source.close()
        assertTrue("expected a full video timeline, got $count packets", count > 100)
    }
}
