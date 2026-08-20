package org.upscalerelay.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            )
        }
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
) {
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

fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("missing '$name'")

fun JsonObject.requiredInt(name: String): Int =
    this[name]?.jsonPrimitive?.int ?: error("missing '$name'")

fun JsonObject.requiredArray(name: String): JsonArray =
    this[name]?.jsonArray ?: error("missing '$name'")
