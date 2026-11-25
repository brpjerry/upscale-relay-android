package org.upscalerelay.android

import org.upscalerelay.protocol.BufferTarget
import org.upscalerelay.protocol.Capabilities
import kotlin.math.roundToLong

/*
 * "Cache size": how much video mpv holds in memory ahead of playback, for
 * relay and local playback alike. For a relay stream the server keeps it
 * filled (PROTOCOL.md §5): the app sizes the server's buffer target to how
 * long the cache lasts at the bitrate it holds, so lower-bitrate video is
 * buffered further ahead and rides out longer network dropouts. The choices
 * and default are the desktop client's.
 */
const val CACHE_DEFAULT_MIB = 768
val CACHE_CHOICES_MIB = listOf(256, 512, 768, 1024, 1536, 2048, 3072, 4096, 5120)

internal const val MIB = 1024L * 1024

fun validCacheMib(value: Int): Int = value.takeIf { it in CACHE_CHOICES_MIB } ?: CACHE_DEFAULT_MIB

/** "768 MB", "1.5 GB" (binary units, as mpv counts them). */
fun formatCacheSize(mib: Int): String =
    if (mib >= 1024) {
        val gib = mib / 1024.0
        if (gib == gib.toLong().toDouble()) "${gib.toLong()} GB" else "$gib GB"
    } else {
        "$mib MB"
    }

/** The quality's coarse P95 bitrate; null for lossless or a server that does not say. */
internal fun tierMbps(capabilities: Capabilities?, tier: String): Double? =
    capabilities?.qualityOptions?.firstOrNull { it.id == tier }?.p95Mbps
        ?.takeIf { it > 0 }?.toDouble()

/** How long the cache lasts at the chosen quality, before any video has arrived to measure. */
internal fun cacheTargetEstimateMillis(cacheMib: Int, capabilities: Capabilities?, tier: String): Long =
    BufferTarget.estimate(cacheMib * MIB, tierMbps(capabilities, tier) ?: BufferTarget.LOSSLESS_MBPS_GUESS)

/**
 * The server's buffer target for what the player holds: how long the whole
 * cache lasts at that bitrate. Null until mpv holds a usable sample; the
 * session then keeps the target it has.
 */
internal fun cacheTargetMillis(cacheMib: Int, cachedBytes: Long, cachedMillis: Long): Long? =
    BufferTarget.forCache(cacheMib * MIB, cachedMillis, cachedBytes)

/**
 * The line under the setting: how long the chosen cache holds at the chosen
 * quality, or that the connected server keeps only its own 10 s. Empty when
 * there is nothing to say (no server yet).
 */
internal fun cacheSizeHint(
    cacheMib: Int,
    capabilities: Capabilities?,
    tier: String,
    serverKeepsFixedBuffer: Boolean,
): String {
    if (serverKeepsFixedBuffer) return "This server keeps only 10 s buffered; update it to fill the cache."
    if (capabilities == null || capabilities.qualityOptions.isEmpty()) return ""
    val mbps = tierMbps(capabilities, tier) ?: BufferTarget.LOSSLESS_MBPS_GUESS
    val seconds = cacheTargetEstimateMillis(cacheMib, capabilities, tier) / 1000.0
    val length = if (seconds < 90) "${seconds.roundToLong()} s" else "${(seconds / 60).roundToLong()} min"
    // A quality's bitrate is a coarse figure; say which, rather than promise a length.
    val rate = if (mbps == mbps.toLong().toDouble()) mbps.toLong().toString() else mbps.toString()
    return "Holds about $length at $rate Mbps. Actual length varies with the video."
}
