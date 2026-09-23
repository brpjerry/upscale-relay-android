package org.upscalerelay.client

import org.upscalerelay.protocol.SeekProgress

/** Monotonic inactivity clock. Repeated ticks and elapsed_s alone are not proof of work. */
internal class SeekProgressTracker(private val nanoTime: () -> Long = System::nanoTime) {
    private var epoch: Int? = null
    private var advancedAt = 0L
    private var indexedSeconds = 0.0

    @Synchronized
    fun begin(epoch: Int) {
        this.epoch = epoch
        indexedSeconds = 0.0
        advancedAt = nanoTime()
    }

    @Synchronized
    fun accept(progress: SeekProgress): Boolean {
        if (progress.epoch != epoch) return false
        val indexed = progress.subtitleIndexedSeconds
        if (progress.stage == "subtitle_index" && indexed != null &&
            indexed.isFinite() && indexed > indexedSeconds
        ) {
            indexedSeconds = indexed
            advancedAt = nanoTime()
        }
        return true
    }

    @Synchronized
    fun idleMillis(): Long? = epoch?.let { (nanoTime() - advancedAt).coerceAtLeast(0) / 1_000_000 }

    @Synchronized
    fun clear() {
        epoch = null
    }
}
