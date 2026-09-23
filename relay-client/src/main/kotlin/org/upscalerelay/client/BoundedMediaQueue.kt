package org.upscalerelay.client

import org.upscalerelay.protocol.MediaPacket
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Byte and item bounds cover both lossless chunks and empty protocol markers. */
class BoundedMediaQueue(
    private val maximumBytes: Long,
    private val maximumPackets: Int = 4096,
) : Closeable {
    private val lock = ReentrantLock()
    private val hasItems = lock.newCondition()
    private val hasSpace = lock.newCondition()
    private val packets = ArrayDeque<MediaPacket>()
    private var closed = false
    private var queuedBytes = 0L

    init {
        require(maximumBytes > 0)
        require(maximumPackets > 0)
    }

    fun put(packet: MediaPacket) {
        require(packet.payload.size.toLong() <= maximumBytes) {
            "single packet exceeds bounded queue capacity"
        }
        lock.withLock {
            while (!closed && (packet.payload.size > maximumBytes - queuedBytes ||
                    packets.size >= maximumPackets)) hasSpace.await()
            check(!closed) { "media queue is closed" }
            packets.addLast(packet)
            queuedBytes += packet.payload.size
            hasItems.signal()
        }
    }

    fun take(): MediaPacket? = lock.withLock {
        while (!closed && packets.isEmpty()) hasItems.await()
        if (packets.isEmpty()) return null
        val packet = packets.removeFirst()
        queuedBytes -= packet.payload.size
        hasSpace.signalAll()
        packet
    }

    fun snapshot(): QueueSnapshot = lock.withLock {
        QueueSnapshot(packets.size, queuedBytes, maximumBytes, closed)
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            packets.clear()
            queuedBytes = 0
            hasItems.signalAll()
            hasSpace.signalAll()
        }
    }
}

data class QueueSnapshot(
    val packets: Int,
    val bytes: Long,
    val maximumBytes: Long,
    val closed: Boolean,
)
