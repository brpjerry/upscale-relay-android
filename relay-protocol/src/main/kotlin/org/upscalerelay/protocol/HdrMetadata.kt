package org.upscalerelay.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * What an uplink says about its source's HDR in open_session.video
 * (PROTOCOL.md §3.5, §3.6), in the units the HEVC SEI messages carry, as the
 * server's relay_media.hdr reads them.
 */

/**
 * SMPTE ST 2086 mastering display colour volume: chromaticities in 0.00002
 * units, luminance in 0.0001 cd/m².
 */
data class MasteringDisplay(
    /** Red, green, blue (x, y). */
    val primaries: List<Pair<Int, Int>>,
    val whitePoint: Pair<Int, Int>,
    val maxLuminance: Long,
    val minLuminance: Long,
) {
    /** What the server accepts: three primaries and every coordinate in range, min below max. */
    val valid: Boolean
        get() = primaries.size == 3 &&
            (primaries + whitePoint).all { (x, y) -> x in 0..MAX_COORDINATE && y in 0..MAX_COORDINATE } &&
            minLuminance >= 0 && minLuminance < maxLuminance && maxLuminance <= MAX_LUMINANCE

    fun toJson(): JsonObject = buildJsonObject {
        put("primaries", JsonArray(primaries.map(::pair)))
        put("white_point", pair(whitePoint))
        put("max_luminance", maxLuminance)
        put("min_luminance", minLuminance)
    }

    private fun pair(value: Pair<Int, Int>) = JsonArray(listOf(JsonPrimitive(value.first), JsonPrimitive(value.second)))

    private companion object {
        const val MAX_COORDINATE = 50_000
        const val MAX_LUMINANCE = 0xFFFF_FFFFL
    }
}

/** CTA-861.3 content light level, in cd/m². */
data class ContentLightLevel(val maxCll: Int, val maxFall: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("max_cll", maxCll)
        put("max_fall", maxFall)
    }
}

/** HDR10 static metadata a container declares; either half may be unknown. */
data class StaticHdrMetadata(
    val masteringDisplay: MasteringDisplay? = null,
    val contentLightLevel: ContentLightLevel? = null,
) {
    /** open_session.video.hdr; null when neither half is known. */
    fun toJson(): JsonObject? {
        if (masteringDisplay == null && contentLightLevel == null) return null
        return buildJsonObject {
            masteringDisplay?.let { put("mastering_display", it.toJson()) }
            contentLightLevel?.let { put("content_light_level", it.toJson()) }
        }
    }
}

/** The fields of a Dolby Vision configuration record (dvcC / dvvC) the server acts on. */
data class DolbyVisionConfig(
    val profile: Int,
    val level: Int,
    val blSignalCompatibilityId: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("profile", profile)
        put("level", level)
        put("bl_signal_compatibility_id", blSignalCompatibilityId)
    }

    companion object {
        /**
         * From a DOVIDecoderConfigurationRecord: version major and minor,
         * then profile (7 bits), level (6), rpu, el and bl present flags, and
         * the base layer's compatibility id (4). Null for a short record or
         * one without an RPU, which leaves nothing to carry or apply.
         */
        fun fromRecord(record: ByteArray): DolbyVisionConfig? {
            if (record.size < 5) return null
            val packed = ((record[2].toInt() and 0xff) shl 8) or (record[3].toInt() and 0xff)
            val rpuPresent = packed shr 2 and 1 == 1
            if (!rpuPresent) return null
            return DolbyVisionConfig(
                profile = packed shr 9,
                level = packed shr 3 and 0x3f,
                blSignalCompatibilityId = (record[4].toInt() and 0xff) shr 4,
            )
        }
    }
}

/**
 * Whether an HEVC access unit, in Annex B framing, carries an HDR10+ (SMPTE
 * ST 2094-40) message: an ITU-T T.35 SEI whose header names Samsung's
 * application 4. What an uplink reports as open_session.video.hdr10plus from
 * its first keyframe (§3.6), the way the server's has_hdr10plus reads it.
 */
fun hevcAccessUnitHasHdr10Plus(accessUnit: ByteArray): Boolean =
    annexBNalUnits(accessUnit).any { nal ->
        nal.size >= 3 && (nal[0].toInt() shr 1 and 0x3f) == NAL_PREFIX_SEI &&
            seiMessages(unescapeRbsp(nal.copyOfRange(2, nal.size))).any { (type, payload) ->
                type == SEI_T35 && payload.size >= HDR10PLUS_T35_PREFIX.size &&
                    HDR10PLUS_T35_PREFIX.indices.all { payload[it] == HDR10PLUS_T35_PREFIX[it] }
            }
    }

private const val NAL_PREFIX_SEI = 39
private const val SEI_T35 = 4

// Country (United States), provider (Samsung), provider-oriented code, application 4.
private val HDR10PLUS_T35_PREFIX = byteArrayOf(0xb5.toByte(), 0x00, 0x3c, 0x00, 0x01, 0x04)

/** Each NAL unit after its start code, trailing zero bytes (a following four-byte start code's) dropped. */
private fun annexBNalUnits(data: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Int>()
    var index = 0
    while (index + 2 < data.size) {
        if (data[index].toInt() == 0 && data[index + 1].toInt() == 0 && data[index + 2].toInt() == 1) {
            starts += index + 3
            index += 3
        } else {
            index++
        }
    }
    return starts.mapIndexedNotNull { position, start ->
        var stop = if (position + 1 < starts.size) starts[position + 1] - 3 else data.size
        while (stop > start && data[stop - 1].toInt() == 0) stop--
        if (stop > start) data.copyOfRange(start, stop) else null
    }
}

/** The RBSP with emulation prevention bytes (00 00 03) removed (H.265 7.4.2). */
private fun unescapeRbsp(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(data.size)
    var zeros = 0
    for (byte in data) {
        if (zeros >= 2 && byte.toInt() == 3) {
            zeros = 0
            continue
        }
        out.write(byte.toInt())
        zeros = if (byte.toInt() == 0) zeros + 1 else 0
    }
    return out.toByteArray()
}

/** (payload type, payload) of each message in an SEI RBSP; a malformed tail ends the list. */
private fun seiMessages(rbsp: ByteArray): List<Pair<Int, ByteArray>> {
    val messages = mutableListOf<Pair<Int, ByteArray>>()
    var position = 0
    fun readValue(): Int? {
        var value = 0
        while (position < rbsp.size && rbsp[position].toInt() and 0xff == 0xff) {
            value += 255
            position++
        }
        if (position >= rbsp.size) return null
        return value + (rbsp[position++].toInt() and 0xff)
    }
    // rbsp_trailing_bits: a lone 0x80 ends the messages.
    while (position < rbsp.size && !(position == rbsp.size - 1 && rbsp[position].toInt() and 0xff == 0x80)) {
        val type = readValue() ?: break
        val size = readValue() ?: break
        if (position + size > rbsp.size) break
        messages += type to rbsp.copyOfRange(position, position + size)
        position += size
    }
    return messages
}
