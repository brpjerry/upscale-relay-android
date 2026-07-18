package org.upscalerelay.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.MediaPacket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BoundedMediaQueueTest {
    @Test
    fun `producer blocks at byte capacity and close wakes it`() {
        val queue = BoundedMediaQueue(4)
        queue.put(MediaPacket(ByteArray(4)))
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var failedClosed = false
        thread {
            entered.countDown()
            failedClosed = runCatching { queue.put(MediaPacket(byteArrayOf(1))) }.isFailure
            finished.countDown()
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
        queue.close()
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertTrue(failedClosed)
        assertNull(queue.take())
    }

    @Test
    fun `taking a packet releases its exact byte count`() {
        val queue = BoundedMediaQueue(8)
        queue.put(MediaPacket(ByteArray(3)))
        queue.put(MediaPacket(ByteArray(5)))
        assertEquals(8, queue.snapshot().bytes)
        assertEquals(3, queue.take()!!.payload.size)
        assertEquals(5, queue.snapshot().bytes)
        queue.close()
    }
}

