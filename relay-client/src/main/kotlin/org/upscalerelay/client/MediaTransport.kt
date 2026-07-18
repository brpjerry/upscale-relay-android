package org.upscalerelay.client

import kotlinx.coroutines.CompletableDeferred
import org.upscalerelay.protocol.MediaFraming
import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class DownlinkReceiver(
    private val host: String,
    private val port: Int,
    private val token: String,
    expectedEpoch: Int,
    queue: BoundedMediaQueue,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    val ready = CompletableDeferred<Unit>()
    private val stopped = AtomicBoolean(false)
    private val bytesReceived = AtomicLong()
    private val packetsReceived = AtomicLong()
    private val completedEpoch = AtomicLong(-1)
    private val route = AtomicReference(EpochRoute(expectedEpoch, queue))
    private val startedNanos = System.nanoTime()
    @Volatile private var socket: Socket? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        worker = thread(name = "relay-android-downlink", isDaemon = true) {
            try {
                val mediaSocket = Socket()
                socket = mediaSocket
                mediaSocket.receiveBufferSize = 4 * 1024 * 1024
                mediaSocket.tcpNoDelay = true
                mediaSocket.connect(InetSocketAddress(host, port), 30_000)
                mediaSocket.soTimeout = 30_000
                val output = mediaSocket.getOutputStream()
                output.write(MediaFraming.handshake(MediaFraming.DIRECTION_DOWNLINK, token))
                output.flush()
                if (mediaSocket.getInputStream().read() != 0) error("downlink handshake rejected")
                ready.complete(Unit)

                var firstPacket = true
                while (!stopped.get()) {
                    val packet = try {
                        MediaFraming.read(mediaSocket.getInputStream())
                    } catch (error: EOFException) {
                        if (completedEpoch.get() >= route.get().epoch) break
                        throw error
                    }
                    if (firstPacket) {
                        firstPacket = false
                        // A user pause may legitimately last indefinitely.
                        // Control-channel heartbeat/failure remains the liveness
                        // signal, and close() interrupts this blocking read.
                        mediaSocket.soTimeout = 0
                    }
                    val destination = route.get()
                    if (packet.epoch < destination.epoch) continue
                    if (packet.epoch > destination.epoch) {
                        error("unexpected future epoch ${packet.epoch}; expected ${destination.epoch}")
                    }
                    bytesReceived.addAndGet(packet.payload.size.toLong())
                    packetsReceived.incrementAndGet()
                    try {
                        destination.queue.put(packet)
                    } catch (error: IllegalStateException) {
                        // A seek closes the old queue to wake a blocked put.
                        // Only suppress that close when this route was in fact
                        // superseded; an unexpected queue close is still fatal.
                        if (stopped.get() || route.get() !== destination) continue
                        throw error
                    }
                    if (packet.endOfStream) completedEpoch.set(packet.epoch.toLong())
                }
                if (!stopped.get() && completedEpoch.get() < route.get().epoch) {
                    throw EOFException("downlink ended before EOS")
                }
            } catch (error: Throwable) {
                if (!ready.isCompleted) ready.completeExceptionally(error)
                if (!stopped.get()) onFailure(error)
            } finally {
                socket?.closeQuietly()
                socket = null
                worker = null
            }
        }
    }

    fun snapshot(): ReceiverSnapshot {
        val elapsed = ((System.nanoTime() - startedNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
        val bytes = bytesReceived.get()
        return ReceiverSnapshot(
            totalBytes = bytes,
            totalPackets = packetsReceived.get(),
            averageMegabitsPerSecond = bytes * 8.0 / elapsed / 1_000_000.0,
        )
    }

    /**
     * Route subsequent packets to a fresh per-mpv-load queue. The downlink
     * socket itself remains connected for the entire relay session.
     */
    fun switchEpoch(expectedEpoch: Int, queue: BoundedMediaQueue) {
        while (true) {
            val previous = route.get()
            require(expectedEpoch > previous.epoch) {
                "new epoch $expectedEpoch must be newer than ${previous.epoch}"
            }
            val next = EpochRoute(expectedEpoch, queue)
            if (route.compareAndSet(previous, next)) {
                previous.queue.close()
                return
            }
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        socket?.closeQuietly()
        // Also wake a receiver blocked in queue.put() before joining it.
        route.get().queue.close()
        if (!ready.isCompleted) ready.cancel()
        worker?.takeUnless { it === Thread.currentThread() }?.join(2_000)
    }
}

private data class EpochRoute(val epoch: Int, val queue: BoundedMediaQueue)

internal class LoopbackMediaServer(
    private val queue: BoundedMediaQueue,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val expectedClientDisconnect = AtomicBoolean(false)
    private val bytesSent = AtomicLong()
    private val server = ServerSocket().apply {
        reuseAddress = false
        // The URL given to mpv is deliberately IPv4. Android commonly returns
        // ::1 from getLoopbackAddress(), which leaves 127.0.0.1 refusing the
        // connection even though this socket appears to be listening.
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        soTimeout = 30_000
    }
    @Volatile private var client: Socket? = null
    @Volatile private var worker: Thread? = null

    val url: String = "tcp://127.0.0.1:${server.localPort}"

    fun start() {
        worker = thread(name = "relay-android-loopback", isDaemon = true) {
            try {
                val accepted = server.accept()
                client = accepted
                accepted.tcpNoDelay = true
                accepted.sendBufferSize = 256 * 1024
                val output = accepted.getOutputStream().buffered(512 * 1024)
                while (!stopped.get()) {
                    val packet = queue.take() ?: break
                    if (packet.payload.isNotEmpty()) {
                        output.write(packet.payload)
                        bytesSent.addAndGet(packet.payload.size.toLong())
                    }
                    if (packet.endOfStream) break
                }
                output.flush()
            } catch (error: Throwable) {
                if (!stopped.get() && !expectedClientDisconnect.get()) onFailure(error)
            } finally {
                client?.closeQuietly()
                client = null
                server.closeQuietly()
                worker = null
            }
        }
    }

    fun totalBytesSent(): Long = bytesSent.get()

    /** The next client-side socket close is the intentional mpv reload. */
    fun expectClientDisconnect() {
        expectedClientDisconnect.set(true)
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        queue.close()
        client?.closeQuietly()
        server.closeQuietly()
        worker?.takeUnless { it === Thread.currentThread() }?.join(2_000)
    }
}

data class ReceiverSnapshot(
    val totalBytes: Long,
    val totalPackets: Long,
    val averageMegabitsPerSecond: Double,
)

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
