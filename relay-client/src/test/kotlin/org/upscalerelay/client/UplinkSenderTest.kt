package org.upscalerelay.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.MediaFraming
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import kotlin.concurrent.thread

class UplinkSenderTest {
    @Test fun `persistent uplink stamps epochs discontinuity and EOS`() {
        val token = "0123456789abcdef0123456789abcdef01"
        val server = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        val packets = Collections.synchronizedList(
            mutableListOf<org.upscalerelay.protocol.MediaPacket>(),
        )
        val complete = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                assertEquals(
                    MediaFraming.handshake(MediaFraming.DIRECTION_UPLINK, token).toList(),
                    socket.getInputStream().readNBytes(MediaFraming.HANDSHAKE_LENGTH).toList(),
                )
                socket.getOutputStream().apply { write(0); flush() }
                repeat(4) { packets += MediaFraming.read(socket.getInputStream()) }
            }
            complete.countDown()
        }
        val source = FakeSource()
        val sender = UplinkSender.connect("127.0.0.1", server.localPort, token, source) {
            throw AssertionError(it)
        }
        sender.startEpoch(0)
        waitFor { packets.size >= 2 }
        sender.startEpoch(1, fromPts = 1_000_000, discontinuity = true)
        assertTrue(complete.await(5, TimeUnit.SECONDS))
        sender.close()
        server.close()
        worker.join(1_000)

        assertEquals(listOf(0, 0, 1, 1), packets.map { it.epoch })
        assertEquals(listOf(false, true, false, true), packets.map { it.endOfStream })
        assertTrue(packets[2].discontinuity)
        assertEquals(1_000_000, packets[2].pts)
        assertEquals(MediaFraming.NO_TIMESTAMP, packets[2].dts)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}

private class FakeSource : UplinkMediaSource {
    override val videoInfo = UplinkVideoInfo(
        name = "sample.mkv", codec = "hevc", extradata = null,
        width = 1920, height = 1080,
        timeBaseNumerator = 1, timeBaseDenominator = 1_000_000,
        averageRateNumerator = 24, averageRateDenominator = 1,
        durationSeconds = 2.0,
    )

    override fun openPacketReader(fromPts: Long?): UplinkPacketReader {
        val pts = fromPts ?: 0
        return object : UplinkPacketReader {
            private var emitted = false
            override fun read(): UplinkAccessUnit? {
                if (emitted) return null
                emitted = true
                return UplinkAccessUnit(byteArrayOf(1, 2, 3), pts, keyframe = true)
            }
            override fun close() = Unit
        }
    }

    override fun close() = Unit
}
