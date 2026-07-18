package org.upscalerelay.client

import org.upscalerelay.protocol.MediaFraming
import org.upscalerelay.protocol.MediaPacket
import java.io.ByteArrayOutputStream
import java.io.Closeable
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
            val ownReader = source.openPacketReader(fromPts)
            reader = ownReader
            worker = thread(name = "relay-android-uplink-$epoch", isDaemon = true) {
                try {
                    val output = socket.getOutputStream()
                    var first = true
                    while (!closed.get() && generation.get() == ownGeneration) {
                        val batch = ByteArrayOutputStream()
                        var count = 0
                        while (count < PACKET_BATCH) {
                            val unit = ownReader.read() ?: break
                            var flags = if (unit.keyframe) MediaFraming.FLAG_KEYFRAME else 0
                            if (first && discontinuity) flags = flags or MediaFraming.FLAG_DISCONTINUITY
                            first = false
                            batch.write(MediaFraming.encode(MediaPacket(
                                payload = unit.payload,
                                flags = flags,
                                epoch = epoch,
                                pts = unit.pts,
                                dts = MediaFraming.NO_TIMESTAMP,
                            )))
                            count += 1
                        }
                        if (batch.size() > 0) {
                            output.write(batch.toByteArray())
                            output.flush()
                        }
                        if (count < PACKET_BATCH) {
                            if (!closed.get() && generation.get() == ownGeneration) {
                                output.write(MediaFraming.encode(MediaPacket(
                                    payload = byteArrayOf(),
                                    flags = MediaFraming.FLAG_END_OF_STREAM,
                                    epoch = epoch,
                                )))
                                output.flush()
                            }
                            break
                        }
                    }
                } catch (error: Throwable) {
                    if (!closed.get() && generation.get() == ownGeneration) onFailure(error)
                } finally {
                    ownReader.closeQuietly()
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
        reader = null
        worker?.takeUnless { it === Thread.currentThread() }?.join(5_000)
        worker = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lifecycleLock) {
            stopGenerationLocked()
            socket.closeQuietly()
            source.closeQuietly()
        }
    }

    companion object {
        private const val PACKET_BATCH = 16

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
