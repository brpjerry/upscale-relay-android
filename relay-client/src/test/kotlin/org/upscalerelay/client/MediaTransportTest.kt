package org.upscalerelay.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.MediaPacket
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MediaTransportTest {
    private val token = "0123456789abcdef0123456789abcdef01"

    @Test
    fun `stale epochs are discarded before the consumer`() = runBlocking {
        val server = serve { output ->
            output.write(MediaFraming.encode(MediaPacket(byteArrayOf(1), epoch = 0)))
            output.write(MediaFraming.encode(MediaPacket(byteArrayOf(2), epoch = 1)))
            output.write(
                MediaFraming.encode(
                    MediaPacket(ByteArray(0), flags = MediaFraming.FLAG_END_OF_STREAM, epoch = 1),
                ),
            )
        }
        val queue = BoundedMediaQueue(1024)
        val failure = CompletableDeferred<Throwable>()
        val receiver = DownlinkReceiver("127.0.0.1", server.localPort, token, 1, queue) {
            failure.complete(it)
        }
        receiver.start()
        withTimeout(2_000) { receiver.ready.await() }
        assertArrayEquals(byteArrayOf(2), withTimeout(2_000) { queue.take() }!!.payload)
        assertTrue(withTimeout(2_000) { queue.take() }!!.endOfStream)
        assertTrue(!failure.isCompleted)
        receiver.close()
        server.close()
    }

    @Test
    fun `future epoch fails the session`() = runBlocking {
        val server = serve { output ->
            output.write(MediaFraming.encode(MediaPacket(byteArrayOf(1), epoch = 1)))
        }
        val queue = BoundedMediaQueue(1024)
        val failure = CompletableDeferred<Throwable>()
        val receiver = DownlinkReceiver("127.0.0.1", server.localPort, token, 0, queue) {
            failure.complete(it)
        }
        receiver.start()
        withTimeout(2_000) { receiver.ready.await() }
        val error = withTimeout(2_000) { failure.await() }
        assertTrue(error.message.orEmpty().contains("future epoch"))
        receiver.close()
        server.close()
    }

    @Test
    fun `epoch switch keeps the socket and routes only new packets to the new queue`() = runBlocking {
        val continueAfterSwitch = CountDownLatch(1)
        val server = serve { output ->
            output.write(MediaFraming.encode(MediaPacket(byteArrayOf(1), epoch = 0)))
            output.flush()
            check(continueAfterSwitch.await(2, TimeUnit.SECONDS))
            output.write(MediaFraming.encode(MediaPacket(byteArrayOf(9), epoch = 0)))
            output.write(MediaFraming.encode(MediaPacket(byteArrayOf(2), epoch = 1)))
            output.write(
                MediaFraming.encode(
                    MediaPacket(ByteArray(0), flags = MediaFraming.FLAG_END_OF_STREAM, epoch = 1),
                ),
            )
        }
        val initialQueue = BoundedMediaQueue(1024)
        val nextQueue = BoundedMediaQueue(1024)
        val failure = CompletableDeferred<Throwable>()
        val receiver = DownlinkReceiver("127.0.0.1", server.localPort, token, 0, initialQueue) {
            failure.complete(it)
        }
        receiver.start()
        withTimeout(2_000) { receiver.ready.await() }
        assertArrayEquals(byteArrayOf(1), withTimeout(2_000) { initialQueue.take() }!!.payload)

        receiver.switchEpoch(1, nextQueue)
        continueAfterSwitch.countDown()

        assertArrayEquals(byteArrayOf(2), withTimeout(2_000) { nextQueue.take() }!!.payload)
        assertTrue(withTimeout(2_000) { nextQueue.take() }!!.endOfStream)
        assertTrue(!failure.isCompleted)
        receiver.close()
        server.close()
    }

    @Test
    fun `truncated payload fails promptly`() = runBlocking {
        val full = MediaFraming.encode(MediaPacket(byteArrayOf(1, 2, 3, 4), epoch = 0))
        val server = serve { output -> output.write(full.copyOf(MediaFraming.HEADER_LENGTH + 2)) }
        val queue = BoundedMediaQueue(1024)
        val failure = CompletableDeferred<Throwable>()
        val receiver = DownlinkReceiver("127.0.0.1", server.localPort, token, 0, queue) {
            failure.complete(it)
        }
        receiver.start()
        withTimeout(2_000) { receiver.ready.await() }
        val error = withTimeout(2_000) { failure.await() }
        assertTrue(error is EOFException)
        receiver.close()
        server.close()
    }

    @Test
    fun `loopback server accepts the ipv4 URL handed to mpv`() {
        val queue = BoundedMediaQueue(1024)
        val failure = CompletableDeferred<Throwable>()
        val loopback = LoopbackMediaServer(queue) { failure.complete(it) }
        val payload = byteArrayOf(1, 2, 3, 4)
        loopback.start()
        queue.put(MediaPacket(payload))
        queue.put(MediaPacket(ByteArray(0), flags = MediaFraming.FLAG_END_OF_STREAM))

        val endpoint = URI(loopback.url)
        Socket(endpoint.host, endpoint.port).use { client ->
            assertArrayEquals(payload, client.getInputStream().readNBytes(payload.size))
        }
        assertTrue(!failure.isCompleted)
        loopback.close()
    }

    private fun serve(writePackets: (java.io.OutputStream) -> Unit): ServerSocket {
        val server = ServerSocket(0)
        thread(name = "relay-test-server", isDaemon = true) {
            server.accept().use { socket ->
                val handshake = socket.getInputStream().readNBytes(MediaFraming.HANDSHAKE_LENGTH)
                check(
                    handshake.contentEquals(
                        MediaFraming.handshake(MediaFraming.DIRECTION_DOWNLINK, token),
                    ),
                )
                socket.getOutputStream().apply {
                    write(0)
                    writePackets(this)
                    flush()
                }
            }
        }
        return server
    }
}
