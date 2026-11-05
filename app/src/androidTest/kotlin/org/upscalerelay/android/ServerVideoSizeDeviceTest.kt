package org.upscalerelay.android

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.upscalerelay.client.HttpRangeReader
import org.upscalerelay.demux.LocalDocumentHttpServer
import org.upscalerelay.demux.ServerVideoSize
import org.upscalerelay.protocol.VideoSize
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * ServerVideoSize on the tablet's own MediaExtractor, served by the app's
 * Range-capable LocalDocumentHttpServer instead of the relay's /media: the
 * size of a 4K Matroska and a 1080p MP4, a failure for a file that is not
 * media, and a cancelled probe that drops its connection at once. Needs no
 * relay; the media cases need HdrUplinkDeviceTest's `dv81.mkv` and
 * `hdr10-boxes-only.mp4` in the debug app's files directory.
 */
@RunWith(AndroidJUnit4::class)
class ServerVideoSizeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun probeServed(file: File) =
        LocalDocumentHttpServer(context, Uri.fromFile(file)).use { server ->
            runBlocking { ServerVideoSize.probe(server.url) }
        }

    private fun fixture(name: String): File =
        File(context.filesDir, name).also { assumeTrue("push $name to the debug app's files", it.isFile) }

    @Test
    fun readsTheSizeOfA4kMatroskaAndA1080pMp4() {
        val matroska = probeServed(fixture("dv81.mkv"))
        assertEquals(VideoSize(3840, 2160), matroska.size)
        val mp4 = probeServed(fixture("hdr10-boxes-only.mp4"))
        assertEquals(VideoSize(1920, 1080), mp4.size)
        for (probed in listOf(matroska, mp4)) {
            assertTrue("$probed", probed.bytesRead in 1..HttpRangeReader.DEFAULT_MAX_BYTES)
        }
    }

    @Test
    fun aFileThatIsNotMediaReadsNoSize() {
        val file = File(context.cacheDir, "not-media.mkv").apply { writeText("not a video\n".repeat(4096)) }
        try {
            val probed = runCatching { probeServed(file) }
            // MediaExtractor refuses it; either way there is no size to act on.
            assertTrue("$probed", probed.isFailure || probed.getOrNull()?.size == null)
        } finally {
            file.delete()
        }
    }

    @Test(timeout = 20_000)
    fun cancellingAProbeOfAServerThatNeverAnswersDropsItsConnection() {
        // 127.0.0.1 itself: Android's getLoopbackAddress() is ::1.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { silent ->
            val asked = CountDownLatch(1)
            val dropped = CountDownLatch(1)
            thread(isDaemon = true) {
                runCatching {
                    silent.accept().use { client ->
                        val input = client.getInputStream()
                        // The request arrives, and no reply ever goes back.
                        if (input.read() >= 0) asked.countDown()
                        while (input.read() >= 0) Unit
                        dropped.countDown()
                    }
                }
            }
            val probed = runBlocking {
                withTimeoutOrNull(3_000) {
                    ServerVideoSize.probe("http://127.0.0.1:${silent.localPort}/media/film.mkv")
                }
            }
            assertNull(probed)
            assertTrue("the probe never asked for anything", asked.await(1, TimeUnit.SECONDS))
            val cancelled = SystemClock.elapsedRealtime()
            // Cancelling closed the reader, which ends the request in flight.
            assertTrue("the connection stayed open", dropped.await(3, TimeUnit.SECONDS))
            assertTrue(SystemClock.elapsedRealtime() - cancelled < 3_000)
        }
    }
}
