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
    @Test fun `canceling a blocked read flushes the preceding packet tail before a new epoch`() {
        val server = ServerSocket(0)
        val reading = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val done = CountDownLatch(1)
        val packets = Collections.synchronizedList(mutableListOf<org.upscalerelay.protocol.MediaPacket>())
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.getInputStream().readNBytes(MediaFraming.HANDSHAKE_LENGTH)
                socket.getOutputStream().write(0)
                repeat(3) { packets += MediaFraming.read(socket.getInputStream()) }
                done.countDown()
            }
        }
        val source = object : FakeSource() {
            override fun openPacketReader(fromPts: Long?): UplinkPacketReader {
                if (fromPts != null) return super.openPacketReader(fromPts)
                return object : UplinkPacketReader {
                    var emitted = false
                    override fun read(): UplinkAccessUnit? {
                        if (!emitted) {
                            emitted = true
                            // Header flushes alone; this body stays in the output buffer.
                            return UplinkAccessUnit(ByteArray(256 * 1024 - 14), 0, true)
                        }
                        reading.countDown()
                        check(canceled.await(3, TimeUnit.SECONDS))
                        throw java.io.IOException("reader canceled")
                    }
                    override fun close() { canceled.countDown() }
                }
            }
        }
        val sender = UplinkSender.connect(
            "127.0.0.1", server.localPort, "0123456789abcdef0123456789abcdef01", source,
        ) { throw AssertionError(it) }
        try {
            sender.startEpoch(0)
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            sender.startEpoch(1, fromPts = 10, discontinuity = true)
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(0, 1, 1), packets.map { it.epoch })
            assertEquals(256 * 1024 - 14, packets[0].payload.size)
            assertTrue(packets[1].discontinuity)
            assertTrue(packets[2].endOfStream)
        } finally {
            canceled.countDown()
            sender.close()
            server.close()
            worker.join(1_000)
        }
    }

    @Test fun `large access unit is sent before attempting another blocking read`() {
        val server = ServerSocket(0)
        val delivered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.getInputStream().readNBytes(MediaFraming.HANDSHAKE_LENGTH)
                socket.getOutputStream().write(0)
                assertEquals(2 * 1024 * 1024, MediaFraming.read(socket.getInputStream()).payload.size)
                delivered.countDown()
                assertTrue(MediaFraming.read(socket.getInputStream()).endOfStream)
                done.countDown()
            }
        }
        val source = object : FakeSource() {
            override fun openPacketReader(fromPts: Long?) = object : UplinkPacketReader {
                var emitted = false
                override fun read(): UplinkAccessUnit? {
                    if (!emitted) {
                        emitted = true
                        return UplinkAccessUnit(ByteArray(2 * 1024 * 1024), 0, true)
                    }
                    check(delivered.await(2, TimeUnit.SECONDS))
                    return null
                }
                override fun close() = Unit
            }
        }
        val sender = UplinkSender.connect(
            "127.0.0.1", server.localPort, "0123456789abcdef0123456789abcdef01", source,
        ) { throw AssertionError(it) }
        try {
            sender.startEpoch(0)
            assertTrue(done.await(4, TimeUnit.SECONDS))
        } finally {
            sender.close()
            server.close()
            worker.join(1_000)
        }
    }

    @Test fun `close reclaims a reader that finishes opening after cancellation`() {
        val server = ServerSocket(0)
        val opening = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val readerClosed = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.getInputStream().readNBytes(MediaFraming.HANDSHAKE_LENGTH)
                socket.getOutputStream().write(0)
                assertEquals(-1, socket.getInputStream().read())
            }
        }
        val source = object : FakeSource() {
            override fun openPacketReader(fromPts: Long?): UplinkPacketReader {
                opening.countDown()
                check(resume.await(2, TimeUnit.SECONDS))
                return object : UplinkPacketReader {
                    override fun read(): UplinkAccessUnit? = error("canceled reader must not read")
                    override fun close() { readerClosed.countDown() }
                }
            }
            override fun close() { resume.countDown() }
        }
        val sender = UplinkSender.connect(
            "127.0.0.1", server.localPort, "0123456789abcdef0123456789abcdef01", source,
        ) { throw AssertionError(it) }
        try {
            sender.startEpoch(0)
            assertTrue(opening.await(1, TimeUnit.SECONDS))
            sender.close()
            assertTrue(readerClosed.await(1, TimeUnit.SECONDS))
        } finally {
            resume.countDown()
            sender.close()
            server.close()
            worker.join(1_000)
        }
    }

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

private open class FakeSource : UplinkMediaSource {
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
