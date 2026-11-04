package org.upscalerelay.demux

import org.upscalerelay.protocol.ContentLightLevel
import org.upscalerelay.protocol.MasteringDisplay
import org.upscalerelay.protocol.StaticHdrMetadata
import java.nio.channels.SeekableByteChannel
import kotlin.math.roundToLong

/**
 * HDR10 static metadata of a Matroska file's first video track, from its
 * Colour element: MaxCLL, MaxFALL and MasteringMetadata.
 *
 * MediaExtractor drops Matroska's mastering display (verified on a Galaxy
 * Tab S9 Ultra, Android 16): its hdr-static-info keeps only the light levels,
 * with zero chromaticities and luminance. A source whose bitstream carries
 * the SEI loses nothing, since the server copies it, but one whose
 * container alone does (VP9 in Matroska has no SEI) would. Best-effort like
 * the chapters: anything unexpected yields null.
 */
object MatroskaHdrStatic {
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_VIDEO = 0xE0L
    private const val ID_COLOUR = 0x55B0L
    private const val ID_MAX_CLL = 0x55BCL
    private const val ID_MAX_FALL = 0x55BDL
    private const val ID_MASTERING_METADATA = 0x55D0L

    // PrimaryR/G/B and WhitePoint chromaticities x and y, then LuminanceMax and LuminanceMin.
    private val MASTERING_IDS = (0x55D1L..0x55DAL).toList()

    private const val TRACK_TYPE_VIDEO = 1L
    private const val MAX_TRACKS_PAYLOAD = 4 * 1024 * 1024

    fun parse(channel: SeekableByteChannel): StaticHdrMetadata? = try {
        MatroskaSegment.readChild(channel, ID_TRACKS, MAX_TRACKS_PAYLOAD)
            ?.let { tracks -> child(firstVideo(tracks) ?: return null, ID_COLOUR) }
            ?.let(::colourMetadata)
    } catch (_: Exception) {
        null
    }

    /** The first video track's Video element: the track MediaExtractor selects. */
    private fun firstVideo(tracks: ByteArray): ByteArray? {
        val cursor = EbmlBytesReader(tracks)
        while (cursor.hasMore) {
            val id = cursor.readElementId()
            val size = cursor.readElementSize() ?: return null
            if (id != ID_TRACK_ENTRY) {
                cursor.skip(size)
                continue
            }
            val entry = cursor.readBytes(size.toInt())
            val type = child(entry, ID_TRACK_TYPE)?.let { EbmlBytesReader(it).readUnsigned(it.size) }
            if (type == TRACK_TYPE_VIDEO) return child(entry, ID_VIDEO)
        }
        return null
    }

    private fun colourMetadata(colour: ByteArray): StaticHdrMetadata? {
        val maxCll = child(colour, ID_MAX_CLL)?.let { EbmlBytesReader(it).readUnsigned(it.size) }
        val maxFall = child(colour, ID_MAX_FALL)?.let { EbmlBytesReader(it).readUnsigned(it.size) }
        val lightLevel = if (maxCll != null || maxFall != null) {
            ContentLightLevel((maxCll ?: 0).toInt(), (maxFall ?: 0).toInt())
                .takeIf { it.maxCll in 0..0xFFFF && it.maxFall in 0..0xFFFF && (it.maxCll > 0 || it.maxFall > 0) }
        } else {
            null
        }
        val mastering = child(colour, ID_MASTERING_METADATA)?.let(::masteringDisplay)
        return StaticHdrMetadata(mastering, lightLevel).takeIf { mastering != null || lightLevel != null }
    }

    /** Chromaticities to 0.00002 units and luminance to 0.0001 cd/m², the SEI's; all ten or none. */
    private fun masteringDisplay(metadata: ByteArray): MasteringDisplay? {
        val values = MASTERING_IDS.map { id -> child(metadata, id)?.let(::float) ?: return null }
        val chroma = values.take(8).map { (it * 50_000).roundToLong().toInt() }
        return MasteringDisplay(
            primaries = listOf(chroma[0] to chroma[1], chroma[2] to chroma[3], chroma[4] to chroma[5]),
            whitePoint = chroma[6] to chroma[7],
            maxLuminance = (values[8] * 10_000).roundToLong(),
            minLuminance = (values[9] * 10_000).roundToLong(),
        ).takeIf { it.valid }
    }

    /** An EBML float: four or eight bytes, big-endian. */
    private fun float(bytes: ByteArray): Double? {
        val bits = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
        return when (bytes.size) {
            4 -> Float.fromBits(bits.toInt()).toDouble()
            8 -> Double.fromBits(bits)
            else -> null
        }?.takeIf { it.isFinite() }
    }

    /** The payload of the first child element [id] of an EBML master's payload. */
    private fun child(master: ByteArray, id: Long): ByteArray? {
        val cursor = EbmlBytesReader(master)
        while (cursor.hasMore) {
            val childId = cursor.readElementId()
            val size = cursor.readElementSize() ?: return null
            if (childId == id) return cursor.readBytes(size.toInt())
            cursor.skip(size)
        }
        return null
    }
}
