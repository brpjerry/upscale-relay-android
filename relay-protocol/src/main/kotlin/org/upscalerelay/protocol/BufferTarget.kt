package org.upscalerelay.protocol

import kotlin.math.roundToLong

/**
 * The client buffer the server keeps topped off (PROTOCOL.md §5).
 *
 * A client asks for its target in `open_session.buffer_target_ms` and may
 * replace it in any `buffer_report`; the server confirms the opening value in
 * `session_opened.buffer_target_ms`. A server that predates the field keeps
 * [DEFAULT_MILLIS], confirms nothing and ignores updates. There is no maximum:
 * the client holds the buffer, so it sizes the target for its own memory.
 */
object BufferTarget {
    const val DEFAULT_MILLIS = 10_000L

    /**
     * The server resumes 500 ms below the target and reports arrive every
     * 500 ms: a target near that would pause and resume on every report.
     */
    const val MIN_MILLIS = 2_000L

    /**
     * A sample shorter than this is trusted only when it fills the cache:
     * extrapolating from the first fraction of a second (often a keyframe or
     * a black opening) misjudges the bitrate by orders of magnitude.
     */
    private const val MIN_SAMPLE_MILLIS = 1_000L
    private const val FULL_CACHE = 0.9

    /** A target as the server applies it: raised to [MIN_MILLIS]; null when unusable. */
    fun normalize(millis: Long?): Long? = millis?.takeIf { it > 0 }?.coerceAtLeast(MIN_MILLIS)

    /**
     * How long a buffer fills [cacheBytes] at the bitrate of what the player
     * holds ([cachedBytes] over [cachedMillis]). Null while the sample is too
     * short to say: under a second, unless the cache is already full, in
     * which case it holds all the video it can and the target is what it
     * holds (a stream above ~2 Gbps fills 256 MiB in under a second).
     */
    fun forCache(cacheBytes: Long, cachedMillis: Long, cachedBytes: Long): Long? {
        if (cacheBytes <= 0 || cachedMillis <= 0 || cachedBytes <= 0) return null
        if (cachedMillis < MIN_SAMPLE_MILLIS && cachedBytes < FULL_CACHE * cacheBytes) return null
        return (cacheBytes.toDouble() * cachedMillis / cachedBytes).roundToLong()
    }

    /**
     * How long [cacheBytes] lasts at [megabitsPerSecond], before any video
     * has arrived to measure: what a session opens with.
     */
    fun estimate(cacheBytes: Long, megabitsPerSecond: Double): Long =
        (cacheBytes * 8.0 / (megabitsPerSecond * 1_000)).roundToLong()

    /** Lossless qualities have no set bitrate; this is about where 4K runs. */
    const val LOSSLESS_MBPS_GUESS = 400.0
}
