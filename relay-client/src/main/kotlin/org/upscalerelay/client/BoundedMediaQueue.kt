package org.upscalerelay.client

import org.upscalerelay.protocol.MediaPacket
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A byte-bounded blocking queue. Packet count alone cannot bound lossless frames. */
class BoundedMediaQueue(private val maximumBytes: Long) : Closeable {
    private val lock = ReentrantLock()
    private val hasItems = lock.newCondition()
    private val hasSpace = lock.newCondition()
    private val packets = ArrayDeque<MediaPacket>()
    private var closed = false
    private var queuedBytes = 0L

    init {
        require(maximumBytes > 0)
    }

    fun put(packet: MediaPacket) {
        require(packet.payload.size.toLong() <= maximumBytes) {
            "single packet exceeds bounded queue capacity"
        }
        lock.withLock {
            while (!closed && queuedBytes + packet.payload.size > maximumBytes) hasSpace.await()
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

