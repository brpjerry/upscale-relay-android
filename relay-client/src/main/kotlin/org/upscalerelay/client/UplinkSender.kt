package org.upscalerelay.client

import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.MediaPacket
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** One persistent uplink socket with one demux generation writing at a time. */
internal class UplinkSender private constructor(
    private val socket: Socket,
    private val source: UplinkMediaSource,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val lifecycleLock = Any()
    @Volatile private var reader: UplinkPacketReader? = null
    @Volatile private var worker: Thread? = null

    fun startEpoch(epoch: Int, fromPts: Long? = null, discontinuity: Boolean = false) {
        synchronized(lifecycleLock) {
            check(!closed.get()) { "uplink is closed" }
            stopGenerationLocked()
            val ownGeneration = generation.incrementAndGet()
            worker = thread(name = "relay-android-uplink-$epoch", isDaemon = true) {
                var ownReader: UplinkPacketReader? = null
                var output: OutputStream? = null
                var writing = false
                fun active() = !closed.get() && generation.get() == ownGeneration
                try {
                    // A document provider can block opening/positioning its descriptor.
                    // Keep that work off the caller, and reclaim late results on cancel.
                    ownReader = source.openPacketReader(fromPts)
                    reader = ownReader
                    if (!active()) return@thread
                    val stream = socket.getOutputStream().buffered(OUTPUT_BUFFER_BYTES)
                    output = stream
                    var first = true
                    var batchBytes = 0
                    var batchPackets = 0
                    while (active()) {
                        val unit = ownReader.read() ?: break
                        if (!active()) break
                        val framedSize = unit.payload.size + MediaFraming.HEADER_LENGTH
                        if (batchBytes > 0 && framedSize > MAX_BATCH_BYTES - batchBytes) {
                            writing = true
                            stream.flush()
                            writing = false
                            batchBytes = 0
                            batchPackets = 0
                        }
                        var flags = if (unit.keyframe) MediaFraming.FLAG_KEYFRAME else 0
                        if (first && discontinuity) flags = flags or MediaFraming.FLAG_DISCONTINUITY
                        first = false
                        // Stream header and payload without packet/batch-sized copies.
                        // A single large access unit writes through the small buffer.
                        writing = true
                        MediaFraming.write(stream, MediaPacket(
                            payload = unit.payload,
                            flags = flags,
                            epoch = epoch,
                            pts = unit.pts,
                            dts = MediaFraming.NO_TIMESTAMP,
                        ))
                        writing = false
                        batchBytes += framedSize
                        batchPackets += 1
                        if (batchBytes >= MAX_BATCH_BYTES || batchPackets >= PACKET_BATCH) {
                            writing = true
                            stream.flush()
                            writing = false
                            batchBytes = 0
                            batchPackets = 0
                        }
                    }
                    if (active()) {
                        writing = true
                        MediaFraming.write(stream, MediaPacket(
                            payload = byteArrayOf(),
                            flags = MediaFraming.FLAG_END_OF_STREAM,
                            epoch = epoch,
                        ))
                        stream.flush()
                        writing = false
                    }
                } catch (error: Throwable) {
                    if (!closed.get() && (active() || writing)) onFailure(error)
                } finally {
                    // Closing the reader can interrupt its next read after a
                    // packet header was flushed but its tail remained buffered.
                    // Complete that frame before permitting another generation.
                    if (!closed.get()) {
                        try { output?.flush() } catch (error: Throwable) {
                            if (!closed.get()) onFailure(error)
                        }
                    }
                    ownReader?.closeQuietly()
                    if (reader === ownReader) reader = null
                    if (worker === Thread.currentThread()) worker = null
                }
            }
        }
    }

    fun stopCurrent() = synchronized(lifecycleLock) { stopGenerationLocked() }

    private fun stopGenerationLocked() {
        generation.incrementAndGet()
        reader?.closeQuietly()
        val previous = worker?.takeUnless { it === Thread.currentThread() }
        previous?.join(5_000)
        if (previous?.isAlive == true) {
            // A blocked writer cannot be allowed to share the socket with the
            // next generation. Abort the session if it cannot retire in time.
            closed.set(true)
            socket.closeQuietly()
            source.closeQuietly()
            previous.join(2_000)
            error("previous uplink generation did not stop")
        }
        reader = null
        worker = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing first interrupts writes even if stopCurrent holds the lock.
        socket.closeQuietly()
        source.closeQuietly()
        synchronized(lifecycleLock) { stopGenerationLocked() }
    }

    companion object {
        private const val PACKET_BATCH = 16
        private const val MAX_BATCH_BYTES = 1024 * 1024
        private const val OUTPUT_BUFFER_BYTES = 256 * 1024

        fun connect(
            host: String,
            port: Int,
            token: String,
            source: UplinkMediaSource,
            onFailure: (Throwable) -> Unit,
        ): UplinkSender {
            val socket = Socket()
            try {
                socket.sendBufferSize = 4 * 1024 * 1024
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 30_000)
                socket.soTimeout = 30_000
                socket.getOutputStream().apply {
                    write(MediaFraming.handshake(MediaFraming.DIRECTION_UPLINK, token))
                    flush()
                }
                check(socket.getInputStream().read() == 0) { "uplink handshake rejected" }
                socket.soTimeout = 0
                return UplinkSender(socket, source, onFailure)
            } catch (error: Throwable) {
                socket.closeQuietly()
                throw error
            }
        }
    }
}
