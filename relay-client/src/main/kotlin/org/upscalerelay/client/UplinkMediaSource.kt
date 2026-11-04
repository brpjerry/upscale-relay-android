package org.upscalerelay.client

import org.upscalerelay.protocol.ChapterInfo
import org.upscalerelay.protocol.DolbyVisionConfig
import org.upscalerelay.protocol.StaticHdrMetadata
import org.upscalerelay.protocol.VideoColor
import org.upscalerelay.protocol.hevcAccessUnitHasHdr10Plus
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
    // What decides and describes a deep-colour downlink (PROTOCOL.md §3.5),
    // each null when the demuxer does not say. The colour is sent only with a
    // known transfer: an "unspecified" one would tell the server the source
    // is SDR, where omitting it lets an HEVC source's parameter sets decide.
    val color: VideoColor? = null,
    val bitDepth: Int? = null,
    val staticHdr: StaticHdrMetadata? = null,
    val dolbyVision: DolbyVisionConfig? = null,
    // The first keyframe carries HDR10+ (§3.6); read only for a session
    // that names a display (firstKeyframeHdr10Plus).
    val hdr10Plus: Boolean = false,
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

/** How many access units [firstKeyframeHdr10Plus] reads for the first keyframe, as the desktop does. */
internal const val HDR10PLUS_SCAN_UNITS = 16

/**
 * Whether the source's first keyframe carries HDR10+, through a packet
 * reader of its own so no epoch's position moves. Only HEVC carries it, and
 * only a PQ source is read (one whose transfer the container leaves unsaid
 * may still be). Blocking; a failed read is false.
 */
internal fun UplinkMediaSource.firstKeyframeHdr10Plus(): Boolean {
    val info = videoInfo
    if (info.codec != "hevc") return false
    if (info.color != null && info.color.transfer != VideoColor.TRANSFER_PQ) return false
    return runCatching {
        openPacketReader(null).use { reader ->
            repeat(HDR10PLUS_SCAN_UNITS) {
                val unit = reader.read() ?: return@use false
                if (unit.keyframe) return@use hevcAccessUnitHasHdr10Plus(unit.payload)
            }
            false
        }
    }.getOrDefault(false)
}
