package org.upscalerelay.demux

import android.media.MediaCodecInfo.CodecProfileLevel
import org.upscalerelay.protocol.ContentLightLevel
import org.upscalerelay.protocol.MasteringDisplay
import org.upscalerelay.protocol.StaticHdrMetadata
import org.upscalerelay.protocol.VideoColor

/*
 * A local source's colour, depth and HDR10 metadata, from MediaExtractor's
 * format keys into what open_session.video carries (PROTOCOL.md §3.5).
 *
 * On the Tab S9 Ultra (Android 16, 2026-11-03) MediaExtractor reports the
 * colour the bitstream declares where it declares one: a Matroska file
 * tagged PQ in its Colour element but HLG in its HEVC VUI read as HLG. That
 * is what the desktop's libav probe reports too. A source whose transfer it
 * does not know (10-bit SDR anime, untagged) has no colour-transfer key.
 */

/**
 * H.273 (primaries, matrix) of MediaFormat's COLOR_STANDARD_* values, and of
 * the one hidden value whose meaning is plain (7, BT.2020 constant
 * luminance). Others mean unspecified.
 */
private val PLATFORM_STANDARDS = mapOf(
    1 to (1 to 1), // BT.709
    2 to (5 to 5), // BT.601 625 (PAL)
    4 to (6 to 6), // BT.601 525 (NTSC)
    6 to (9 to 9), // BT.2020
    7 to (9 to 10), // BT.2020 constant luminance
)

/** H.273 transfer of MediaFormat's COLOR_TRANSFER_* values and ColorUtils' hidden ones. */
private val PLATFORM_TRANSFERS = mapOf(
    1 to 8, // linear
    2 to 13, // sRGB
    3 to 1, // SDR video: BT.709 and SMPTE 170M, which Android does not tell apart
    4 to 4, // gamma 2.2
    5 to 5, // gamma 2.8
    6 to VideoColor.TRANSFER_PQ, // SMPTE ST 2084
    7 to VideoColor.TRANSFER_HLG,
    0x40 to 7, // SMPTE 240M
    0x41 to 11, // xvYCC
    0x42 to 12, // BT.1361
    0x43 to 17, // SMPTE ST 428
)

private const val UNSPECIFIED = 2

/**
 * open_session.video.color from MediaFormat's color-standard, -transfer and
 * -range; null without a transfer it can name. An unspecified transfer
 * would tell the server the source is SDR, where omitting the colour lets an
 * HEVC source's parameter sets decide.
 */
internal fun platformColor(standard: Int?, transfer: Int?, range: Int?): VideoColor? {
    val code = transfer?.let(PLATFORM_TRANSFERS::get) ?: return null
    val (primaries, matrix) = standard?.let(PLATFORM_STANDARDS::get) ?: (UNSPECIFIED to UNSPECIFIED)
    return VideoColor(
        primaries = primaries,
        transfer = code,
        matrix = matrix,
        range = when (range) {
            1 -> "full"
            2 -> "limited"
            else -> null
        },
    )
}

/**
 * Bits per sample of the decoded format: MediaExtractor's bits-per-sample
 * where it reports one (HEVC), else what the codec profile implies. Null
 * when neither tells, such as H.264 High 4:2:2, which allows 8 bits or more.
 */
internal fun sourceBitDepth(bitsPerSample: Int?, codec: String, profile: Int?): Int? {
    bitsPerSample?.takeIf { it in 8..16 }?.let { return it }
    if (profile == null) return null
    return when (codec) {
        "hevc" -> when (profile) {
            CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCProfileMainStill -> 8
            CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10,
            CodecProfileLevel.HEVCProfileMain10HDR10Plus -> 10
            else -> null
        }
        "h264" -> when (profile) {
            CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileConstrainedBaseline,
            CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCProfileExtended,
            CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileConstrainedHigh -> 8
            CodecProfileLevel.AVCProfileHigh10 -> 10
            else -> null
        }
        "vp9" -> when (profile) {
            CodecProfileLevel.VP9Profile0, CodecProfileLevel.VP9Profile1 -> 8
            CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile3,
            CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile3HDR,
            CodecProfileLevel.VP9Profile2HDR10Plus, CodecProfileLevel.VP9Profile3HDR10Plus -> 10
            else -> null
        }
        "av1" -> when (profile) {
            CodecProfileLevel.AV1ProfileMain8 -> 8
            CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10,
            CodecProfileLevel.AV1ProfileMain10HDR10Plus -> 10
            else -> null
        }
        else -> null
    }
}

/**
 * open_session.video.hdr from MediaFormat's hdr-static-info: CTA-861.3
 * static metadata type 1, little-endian. A type byte of 0, then the R, G, B
 * and white chromaticities in 0.00002 units, the mastering display's peak in
 * cd/m² and its minimum in 0.0001 cd/m², MaxCLL and MaxFALL. Zero
 * chromaticities mean the mastering display is unknown (a container that
 * carries only light levels); zero light levels, the light levels.
 */
internal fun staticHdrFromCta861(info: ByteArray?): StaticHdrMetadata? {
    if (info == null || info.size < CTA861_TYPE1_BYTES || info[0].toInt() != 0) return null
    val field = IntArray(12) { index ->
        (info[1 + 2 * index].toInt() and 0xff) or ((info[2 + 2 * index].toInt() and 0xff) shl 8)
    }
    val primaries = listOf(field[0] to field[1], field[2] to field[3], field[4] to field[5])
    val white = field[6] to field[7]
    val mastering = MasteringDisplay(
        primaries = primaries,
        whitePoint = white,
        maxLuminance = field[8] * 10_000L,
        minLuminance = field[9].toLong(),
    ).takeIf { display -> (primaries + white).none { it.first == 0 || it.second == 0 } && display.valid }
    val lightLevel = ContentLightLevel(field[10], field[11]).takeIf { it.maxCll > 0 || it.maxFall > 0 }
    return StaticHdrMetadata(mastering, lightLevel).takeIf { mastering != null || lightLevel != null }
}

private const val CTA861_TYPE1_BYTES = 25

/** Each half from [this] where it is known, else from [fallback]. */
internal fun StaticHdrMetadata?.orElse(fallback: StaticHdrMetadata?): StaticHdrMetadata? {
    val mastering = this?.masteringDisplay ?: fallback?.masteringDisplay
    val lightLevel = this?.contentLightLevel ?: fallback?.contentLightLevel
    return StaticHdrMetadata(mastering, lightLevel).takeIf { mastering != null || lightLevel != null }
}

/**
 * The base layer's codec, which the server decodes, of a Dolby Vision
 * profile: AVC for 0, 1 and 9, AV1 for 10, HEVC for the rest of 2-8.
 */
internal fun dolbyVisionBaseCodec(profile: Int): String? = when (profile) {
    0, 1, 9 -> "h264"
    in 2..8 -> "hevc"
    10 -> "av1"
    else -> null
}

/** The same for a track MediaExtractor reports as video/dolby-vision with only its MediaFormat profile. */
internal fun dolbyVisionBaseCodecOfPlatformProfile(profile: Int?): String = when (profile) {
    CodecProfileLevel.DolbyVisionProfileDvavPer, CodecProfileLevel.DolbyVisionProfileDvavPen,
    CodecProfileLevel.DolbyVisionProfileDvavSe -> "h264"
    CodecProfileLevel.DolbyVisionProfileDvav110 -> "av1"
    else -> "hevc"
}
