package org.upscalerelay.client

import org.upscalerelay.protocol.ChapterInfo
import java.io.Closeable

/** Encoded local-video metadata required by protocol v1 open_session. */
data class UplinkVideoInfo(
    val name: String,
    val codec: String,
    val extradata: ByteArray?,
    val width: Int,
    val height: Int,
    val timeBaseNumerator: Int,
    val timeBaseDenominator: Int,
    val averageRateNumerator: Int?,
    val averageRateDenominator: Int?,
    val durationSeconds: Double?,
    // Sent as open_session.file.chapters; the server echoes them back in
    // session_opened so both playback sources read chapters the same way.
    val chapters: List<ChapterInfo> = emptyList(),
    val sourceHasAudio: Boolean? = null,
    val sourceHasAuxiliary: Boolean? = null,
    // Pixel (sample) aspect ratio of the coded frames, when the extractor
    // reports one. Anamorphic sources need it to be fitted by display aspect.
    val sampleAspectNumerator: Int? = null,
    val sampleAspectDenominator: Int? = null,
) {
    /**
     * The ratio worth sending: reduced, positive, not square, and within the
     * 1/10..10 that PROTOCOL.md calls plausible (the server reads anything
     * else as square, so it is not sent). Omitted means square pixels.
     */
    fun anamorphicSampleAspect(): Pair<Int, Int>? {
        val numerator = sampleAspectNumerator?.takeIf { it > 0 } ?: return null
        val denominator = sampleAspectDenominator?.takeIf { it > 0 } ?: return null
        if (numerator == denominator) return null
        if (numerator.toLong() > denominator * 10L || denominator.toLong() > numerator * 10L) return null
        val divisor = gcd(numerator, denominator)
        return numerator / divisor to denominator / divisor
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

data class UplinkAccessUnit(
    val payload: ByteArray,
    val pts: Long,
    val keyframe: Boolean,
)

/**
 * Each reader owns an independent demuxer/descriptor generation. Closing a
 * superseded reader must make its next read finish promptly and can never move
 * the read position of a newer seek generation.
 */
interface UplinkPacketReader : Closeable {
    fun read(): UplinkAccessUnit?
}

interface UplinkMediaSource : Closeable {
    val videoInfo: UplinkVideoInfo
    fun openPacketReader(fromPts: Long?): UplinkPacketReader
}
