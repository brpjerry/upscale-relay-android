package org.upscalerelay.client

/**
 * Pacing and budget for automatic reconnect/resume after a recoverable
 * failure. Attempts are 1-based; delays back off exponentially and cap so a
 * ten-second network interruption is always covered well inside the budget.
 */
class ReconnectPolicy(
    val maxAttempts: Int = 6,
    val initialDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 8_000,
    val budgetMillis: Long = 60_000,
) {
    init {
        require(maxAttempts >= 1)
        require(initialDelayMillis >= 0 && maxDelayMillis >= initialDelayMillis)
    }

    /** Delay to wait before the given 1-based attempt. */
    fun delayBeforeAttempt(attempt: Int): Long {
        require(attempt >= 1)
        var delay = initialDelayMillis
        repeat(attempt - 1) {
            delay = (delay * 2).coerceAtMost(maxDelayMillis)
            if (delay == maxDelayMillis) return delay
        }
        return delay.coerceAtMost(maxDelayMillis)
    }

    /** Whether the given 1-based attempt may run at all. */
    fun shouldAttempt(attempt: Int, elapsedMillis: Long, kind: FailureKind): Boolean =
        kind.recoverable && attempt <= maxAttempts && elapsedMillis < budgetMillis
}
