package org.upscalerelay.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class DisplaySize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

data class Rational(val numerator: Int, val denominator: Int) {
    init {
        require(denominator != 0) { "rational denominator must not be zero" }
    }

    val value: Double get() = numerator.toDouble() / denominator

    companion object {
        fun fromJson(value: JsonArray): Rational {
            require(value.size == 2) { "rational must contain numerator and denominator" }
            return Rational(value[0].jsonPrimitive.int, value[1].jsonPrimitive.int)
        }
    }
}

data class ModelInfo(val name: String, val scaleFactor: Int?)

data class QualityOption(
    val id: String,
    val label: String,
    val codec: String,
    val lossless: Boolean,
    val androidSupported: Boolean,
    val p95Mbps: Int?,
)

/**
 * What PROTOCOL.md guarantees of capabilities.server_id. Anything else —
 * empty, too long, another charset, not a string — counts as absent.
 */
private val SERVER_ID = Regex("[A-Za-z0-9_-]{1,64}")

data class Capabilities(
    val protocolVersion: Int,
    val serverName: String,
    val models: List<ModelInfo>,
    val qualityTiers: List<String>,
    val qualityOptions: List<QualityOption>,
    val hasLibrary: Boolean,
    val resizeAlgorithms: List<String>,
    val defaultResizeAlgorithm: String,
    /** Sort keys GET /library accepts ("name", "mtime"); empty on old servers. */
    val librarySortKeys: List<String> = emptyList(),
    /** Server-file sessions may opt in to in-band audio/subtitle tracks. */
    val muxedAuxTracks: Boolean = false,
    /** Content-addressed subtitle attachment protocol version; zero means absent. */
    val attachmentCacheVersion: Int = 0,
    /**
     * The server's stable identity, unchanged across restarts and address
     * changes; null from servers that predate it. Per-server client state
     * (watch history, recents) is scoped by it.
     */
    val serverId: String? = null,
    /** open_session may ask for a 10-bit downlink (PROTOCOL.md §3.5). */
    val highBitDepth: Boolean = false,
    /**
     * open_session and seek may name the display (§3.6), and the server then
     * applies Dolby Vision and HDR10+ for it.
     */
    val displayMapping: Boolean = false,
) {
    val phaseOneModel: String
        get() = models.firstOrNull { it.name != "passthrough" }?.name ?: "passthrough"

    companion object {
        fun fromJson(value: JsonObject): Capabilities {
            val qualityTiers = value.requiredArray("quality_tiers")
                .map { it.jsonPrimitive.content }
            val qualityOptions = value["quality_options"]?.jsonArray?.map { element ->
                val option = element.jsonObject
                QualityOption(
                    id = option.requiredString("id"),
                    label = option.requiredString("label"),
                    codec = option.requiredString("codec"),
                    lossless = option["lossless"]?.jsonPrimitive?.booleanOrNull ?: false,
                    androidSupported = option["android_supported"]
                        ?.jsonPrimitive?.booleanOrNull ?: false,
                    p95Mbps = option["p95_mbps"]?.jsonPrimitive?.intOrNull,
                )
            } ?: qualityTiers.map { tier ->
                QualityOption(
                    id = tier,
                    label = tier,
                    codec = if (tier == "lossless-ffv1") "ffv1" else "hevc",
                    lossless = tier.startsWith("lossless-"),
                    androidSupported = tier != "lossless-ffv1",
                    p95Mbps = null,
                )
            }
            return Capabilities(
                protocolVersion = value.requiredInt("protocol_version"),
                serverName = value.requiredString("server_name"),
                models = value.requiredArray("models").map {
                    val model = it.jsonObject
                    ModelInfo(
                        name = model.requiredString("name"),
                        scaleFactor = model["scale_factor"]?.jsonPrimitive?.intOrNull,
                    )
                },
                qualityTiers = qualityTiers,
                qualityOptions = qualityOptions,
                hasLibrary = value["library"]?.jsonPrimitive?.booleanOrNull ?: false,
                resizeAlgorithms = value["resize_algorithms"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: listOf("lanczos"),
                defaultResizeAlgorithm = value["default_resize_algorithm"]
                    ?.jsonPrimitive?.content ?: "lanczos",
                librarySortKeys = value["library_sort"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: emptyList(),
                muxedAuxTracks = value["muxed_aux_tracks"]?.jsonPrimitive?.booleanOrNull ?: false,
                attachmentCacheVersion = value["attachment_cache"]?.jsonPrimitive?.intOrNull
                    ?.coerceAtLeast(0) ?: 0,
                serverId = (value["server_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf(SERVER_ID::matches),
                highBitDepth = value["high_bit_depth"].wireBoolean(),
                displayMapping = value["display_mapping"].wireBoolean(),
            )
        }
    }
}

/**
 * What GET /status says about sessions the server holds. A client that could
 * not get a `closed` acknowledgement, because the control connection was
 * already gone, reads it on its next connection: the server releases a
 * session by itself when its connection drops, and this is how that went.
 */
data class ServerStatus(
    /** Sessions still held, by `session_id`. */
    val sessionIds: Set<String>,
    /** A native teardown failed; the server refuses new sessions until restarted. */
    val restartRequired: Boolean,
    /** The session whose teardown failed, when one did. */
    val failedTeardownSessionId: String?,
) {
    companion object {
        fun fromJson(value: JsonObject): ServerStatus = ServerStatus(
            sessionIds = value.requiredArray("sessions")
                .map { it.jsonObject.requiredString("id") }.toSet(),
            restartRequired = value["restart_required"]?.jsonPrimitive?.booleanOrNull ?: false,
            failedTeardownSessionId = (value["native_teardown_error"] as? JsonObject)
                ?.get("session_id")?.jsonPrimitive?.contentOrNull,
        )
    }
}

data class AttachmentManifestEntry(
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
) {
    companion object {
        const val MAX_NAME_LENGTH = 128
        const val MAX_ATTACHMENT_BYTES = 64L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256L * 1024 * 1024
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]+")

        fun fromJson(value: JsonObject): AttachmentManifestEntry {
            val digest = value.requiredString("sha256")
            require(SHA256.matches(digest)) { "invalid attachment hash" }
            val size = value["size"]?.jsonPrimitive?.content?.toLongOrNull()
            require(size != null && size in 0..MAX_ATTACHMENT_BYTES) {
                "invalid attachment size"
            }
            val name = sanitizeName(value["name"]?.jsonPrimitive?.content, digest)
            val mimeType = value["mimetype"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"
            return AttachmentManifestEntry(name, mimeType, size, digest)
        }

        private fun sanitizeName(raw: String?, digest: String): String {
            val basename = raw.orEmpty().replace('\\', '/').substringAfterLast('/')
            val printable = basename.filter { it >= ' ' && it != '\u007f' }
            return UNSAFE_NAME.replace(printable, "_")
                .trim(' ', '.', '_')
                .take(MAX_NAME_LENGTH)
                .ifBlank { "font-${digest.take(12)}" }
        }
    }
}

data class LibraryNode(
    val type: Type,
    val name: String,
    val path: String,
    val children: List<LibraryNode> = emptyList(),
) {
    enum class Type { DIRECTORY, FILE }

    companion object {
        fun fromJson(value: JsonObject): LibraryNode {
            val type = when (value.requiredString("type")) {
                "directory" -> Type.DIRECTORY
                "file" -> Type.FILE
                else -> error("unknown library node type")
            }
            return LibraryNode(
                type = type,
                name = value.requiredString("name"),
                path = value.requiredString("path"),
                children = value["children"]?.jsonArray?.map { fromJson(it.jsonObject) }.orEmpty(),
            )
        }
    }
}

data class LibraryPage(
    val directory: LibraryNode,
    val nextCursor: String?,
) {
    companion object {
        fun fromJson(value: JsonObject): LibraryPage = LibraryPage(
            directory = LibraryNode.fromJson(value.getValue("tree").jsonObject),
            nextCursor = value["next_cursor"]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.content,
        )
    }
}

/**
 * A chapter mark of the source media, in seconds of source-media time
 * (session_opened.chapters). Seeking to one goes through the ordinary
 * time_base conversion like any other seek target.
 */
data class ChapterInfo(
    val startSeconds: Double,
    val endSeconds: Double?,
    val title: String?,
) {
    companion object {
        fun fromJson(value: JsonObject): ChapterInfo? {
            val start = value["start_s"]?.takeUnless { it is JsonNull }
                ?.jsonPrimitive?.doubleOrNull ?: return null
            if (start < 0) return null
            return ChapterInfo(
                startSeconds = start,
                endSeconds = value["end_s"]?.takeUnless { it is JsonNull }
                    ?.jsonPrimitive?.doubleOrNull,
                title = value["title"]?.takeUnless { it is JsonNull }
                    ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** open_session.high_bit_depth (PROTOCOL.md §3.5). */
enum class HighBitDepth(val wire: String) {
    /** PQ and HLG sources at 10 bits with their HDR metadata, the rest at 8. */
    HDR("hdr"),

    /** Every source at 10 bits, an 8-bit one widened. */
    ALL("all"),
}

/**
 * A video's colour description as the protocol carries it: ITU-T H.273 code
 * points (FFmpeg's enum values, 2 for unspecified), and the range by name
 * when it is known.
 */
data class VideoColor(
    val primaries: Int,
    val transfer: Int,
    val matrix: Int,
    val range: String? = null,
) {
    /** PQ or HLG. */
    val isHdr: Boolean get() = transfer == TRANSFER_PQ || transfer == TRANSFER_HLG

    fun toJson(): JsonObject = buildJsonObject {
        put("primaries", primaries)
        put("transfer", transfer)
        put("matrix", matrix)
        range?.let { put("range", it) }
    }

    companion object {
        const val TRANSFER_PQ = 16
        const val TRANSFER_HLG = 18
        private val RANGES = setOf("limited", "full")

        /**
         * Null for anything but an object with three code points. The colour
         * is diagnostic on this side, so an unknown range is dropped rather
         * than failing the session.
         */
        fun fromJson(value: JsonElement?): VideoColor? {
            val color = value as? JsonObject ?: return null
            fun code(name: String) = color[name].wireInt()?.takeIf { it in 0..255 }
            return VideoColor(
                primaries = code("primaries") ?: return null,
                transfer = code("transfer") ?: return null,
                matrix = code("matrix") ?: return null,
                range = (color["range"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it in RANGES },
            )
        }
    }
}

/**
 * open_session / seek display_target (PROTOCOL.md §3.6): the display the
 * client presents on. "pq" shows PQ as HDR up to [peakNits]; "sdr" shows SDR.
 * Null luminances: the platform does not say.
 */
data class DisplayTarget(
    val transfer: String,
    val peakNits: Double? = null,
    val sdrWhiteNits: Double? = null,
) {
    init {
        require(transfer in TRANSFERS) { "unknown display transfer '$transfer'" }
    }

    /** The server ignores luminances outside 0..10000 nits, so none are sent. */
    fun toJson(): JsonObject = buildJsonObject {
        put("transfer", transfer)
        peakNits?.takeIf(::plausibleNits)?.let { put("peak_nits", it) }
        sdrWhiteNits?.takeIf(::plausibleNits)?.let { put("sdr_white_nits", it) }
    }

    companion object {
        private val TRANSFERS = setOf("pq", "sdr")

        /** A display that shows SDR, at a white level the platform does not report. */
        val SDR = DisplayTarget("sdr")

        private fun plausibleNits(value: Double) = value.isFinite() && value > 0 && value <= 10_000
    }
}

/**
 * session_opened / seek_ready downlink_mapping (PROTOCOL.md §3.6): what the
 * server applied to an epoch for the display it was made for. An empty
 * [applied] means that epoch streams as §3.5 says.
 */
data class DownlinkMapping(
    val transfer: String?,
    val peakNits: Double?,
    val sdrWhiteNits: Double?,
    /** "dolby_vision", "hdr10plus"; unknown names are kept as they came. */
    val applied: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("transfer", transfer)
        put("peak_nits", peakNits)
        put("sdr_white_nits", sdrWhiteNits)
        put("applied", JsonArray(applied.map(::JsonPrimitive)))
    }

    companion object {
        /** Null for anything but an object: a session the server never maps. */
        fun fromJson(value: JsonElement?): DownlinkMapping? {
            val mapping = value as? JsonObject ?: return null
            return DownlinkMapping(
                transfer = (mapping["transfer"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                peakNits = mapping["peak_nits"].wireNumber(),
                sdrWhiteNits = mapping["sdr_white_nits"].wireNumber(),
                applied = (mapping["applied"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content },
            )
        }
    }
}

data class SessionInfo(
    val sessionId: String,
    val mediaPort: Int,
    val uplinkToken: String?,
    val downlinkToken: String,
    val downlinkCodec: String,
    val downlinkWidth: Int,
    val downlinkHeight: Int,
    val downlinkContainer: String?,
    val epoch: Int,
    val durationSeconds: Double?,
    val timeBase: Rational?,
    val averageRate: Rational?,
    val fitMode: String,
    val resizeAlgorithm: String?,
    val chapters: List<ChapterInfo> = emptyList(),
    val source: String = "uplink",
    /** Effective server confirmation; unknown values intentionally fall back safely. */
    val auxTracks: String = "external",
    /** Effective attachment confirmation; unknown values intentionally mean embedded. */
    val auxAttachments: String = "embedded",
    val attachmentManifest: List<AttachmentManifestEntry> = emptyList(),
    /** Ephemeral bearer token. Never include this value in logs or persisted state. */
    val attachmentToken: String? = null,
    /** Null is unknown, including compatibility with servers predating these fields. */
    val sourceHasAudio: Boolean? = null,
    /** Includes audio OR subtitles; false alone authorizes omitting external media. */
    val sourceHasAuxiliary: Boolean? = null,
    /** 8 or 10 (PROTOCOL.md §3.5); 8 from servers that predate the field. */
    val downlinkBitDepth: Int = 8,
    /** The colour the current epoch is encoded with; null when the server does not know it. */
    val downlinkColor: VideoColor? = null,
    /** A Dolby Vision profile 8 RPU rides the downlink. */
    val downlinkDolbyVision: Boolean = false,
    /** The source's bits per sample; null when the server did not say. */
    val sourceBitDepth: Int? = null,
    /**
     * What the server applied to the current epoch for the client's display
     * (§3.6); null on a session it never maps.
     */
    val downlinkMapping: DownlinkMapping? = null,
) {
    /**
     * This session as of the epoch a seek_ready announced. On a mapped
     * session each epoch says what it carries, and an epoch made for another
     * display can switch between PQ and SDR (§3.6); other sessions' epochs
     * keep the session's colour, and their seek_ready says nothing of it.
     */
    fun describedBy(seekReady: JsonObject): SessionInfo {
        if ("downlink_mapping" !in seekReady) return this
        return copy(
            downlinkMapping = DownlinkMapping.fromJson(seekReady["downlink_mapping"]),
            downlinkColor = VideoColor.fromJson(seekReady["downlink_color"]) ?: downlinkColor,
        )
    }

    companion object {
        fun fromJson(value: JsonObject): SessionInfo {
            val auxTracks = value["aux_tracks"]?.jsonPrimitive?.content
                ?.takeIf { it == "muxed" } ?: "external"
            val auxAttachments = value["aux_attachments"]?.jsonPrimitive?.content
                ?.takeIf { it == "cached" } ?: "embedded"
            val manifest = if (auxTracks == "muxed" && auxAttachments == "cached") {
                parseAttachmentManifest(value["attachment_manifest"])
            } else {
                emptyList()
            }
            val attachmentToken = if (auxTracks == "muxed" && auxAttachments == "cached") {
                value["attachment_token"]?.takeUnless { it is JsonNull }
                    ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: error("cached attachment session omitted its token")
            } else {
                null
            }
            return SessionInfo(
            sessionId = value.requiredString("session_id"),
            mediaPort = value.requiredInt("media_port"),
            uplinkToken = value["uplink_token"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            downlinkToken = value.requiredString("downlink_token"),
            downlinkCodec = value.requiredString("downlink_codec"),
            downlinkWidth = value.requiredInt("downlink_width"),
            downlinkHeight = value.requiredInt("downlink_height"),
            downlinkContainer = value["downlink_container"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            epoch = value["epoch"]?.jsonPrimitive?.int ?: 0,
            durationSeconds = value["duration_s"]?.jsonPrimitive?.doubleOrNull,
            timeBase = value["time_base"]?.takeUnless { it is JsonNull }
                ?.jsonArray?.let(Rational::fromJson),
            averageRate = value["avg_rate"]?.takeUnless { it is JsonNull }
                ?.jsonArray?.let(Rational::fromJson),
            fitMode = value["fit_mode"]?.jsonPrimitive?.content ?: "fit",
            resizeAlgorithm = value["resize_algorithm"]?.takeUnless { it is JsonNull }
                ?.jsonPrimitive?.content,
            chapters = value["chapters"]?.takeUnless { it is JsonNull }?.jsonArray
                ?.mapNotNull { element ->
                    runCatching { ChapterInfo.fromJson(element.jsonObject) }.getOrNull()
                }
                ?.sortedBy { it.startSeconds }
                .orEmpty(),
            source = value["source"]?.jsonPrimitive?.content
                ?: if (value["uplink_token"] == null || value["uplink_token"] is JsonNull) {
                    "server_file"
                } else {
                    "uplink"
                },
            auxTracks = auxTracks,
            auxAttachments = auxAttachments,
            attachmentManifest = manifest,
            attachmentToken = attachmentToken,
            sourceHasAudio = value["source_has_audio"]?.jsonPrimitive?.booleanOrNull,
            sourceHasAuxiliary = value["source_has_auxiliary"]?.jsonPrimitive?.booleanOrNull,
            downlinkBitDepth = value["downlink_bit_depth"].wireInt()?.takeIf { it == 8 || it == 10 } ?: 8,
            downlinkColor = VideoColor.fromJson(value["downlink_color"]),
            downlinkDolbyVision = value["downlink_dolby_vision"].wireBoolean(),
            sourceBitDepth = value["source_bit_depth"].wireInt()?.takeIf { it > 0 },
            downlinkMapping = DownlinkMapping.fromJson(value["downlink_mapping"]),
        )
        }

        private fun parseAttachmentManifest(value: kotlinx.serialization.json.JsonElement?): List<AttachmentManifestEntry> {
            val array = value?.takeUnless { it is JsonNull }?.jsonArray
                ?: error("cached attachment session omitted its manifest")
            var total = 0L
            val uniqueSizes = mutableMapOf<String, Long>()
            return array.map { element ->
                val entry = AttachmentManifestEntry.fromJson(element.jsonObject)
                val prior = uniqueSizes.putIfAbsent(entry.sha256, entry.size)
                require(prior == null || prior == entry.size) {
                    "duplicate attachment hash has inconsistent size"
                }
                if (prior == null) {
                    total += entry.size
                    require(total <= AttachmentManifestEntry.MAX_MANIFEST_BYTES) {
                        "attachment manifest exceeds session size limit"
                    }
                }
                entry
            }
        }
    }
}

/** Informational source coverage during a seek; indexed seconds are not a playback position. */
data class SeekProgress(
    val epoch: Int,
    val targetPts: Long,
    val keyframePts: Long?,
    val framesDiscarded: Int,
    val elapsedSeconds: Double?,
    val stage: String?,
    val message: String?,
    val subtitleIndexedSeconds: Double?,
) {
    companion object {
        fun fromJson(value: JsonObject): SeekProgress = SeekProgress(
            epoch = value.requiredInt("epoch"),
            targetPts = requireNotNull(value["target_pts"]?.jsonPrimitive?.longOrNull),
            keyframePts = value["keyframe_pts"]?.jsonPrimitive?.longOrNull,
            framesDiscarded = value["frames_discarded"]?.jsonPrimitive?.intOrNull ?: 0,
            elapsedSeconds = value["elapsed_s"]?.jsonPrimitive?.doubleOrNull
                ?.takeIf { it.isFinite() && it >= 0 },
            stage = value["stage"]?.jsonPrimitive?.contentOrNull,
            message = value["message"]?.jsonPrimitive?.contentOrNull,
            subtitleIndexedSeconds = value["subtitle_indexed_s"]?.jsonPrimitive?.doubleOrNull
                ?.takeIf { it.isFinite() && it >= 0 },
        )
    }
}

fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("missing '$name'")

fun JsonObject.requiredInt(name: String): Int =
    this[name]?.jsonPrimitive?.int ?: error("missing '$name'")

fun JsonObject.requiredArray(name: String): JsonArray =
    this[name]?.jsonArray ?: error("missing '$name'")

/*
 * Optional fields read as the server writes them. Anything else (a string,
 * an object, a null) is absent, never an error: a malformed optional field
 * must not fail the message that carries it.
 */
private fun JsonElement?.wireBoolean(): Boolean =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

private fun JsonElement?.wireInt(): Int? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun JsonElement?.wireNumber(): Double? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
