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
            )
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
) {
    companion object {
        fun fromJson(value: JsonObject) = SessionInfo(
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
        )
    }
}

fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("missing '$name'")

fun JsonObject.requiredInt(name: String): Int =
    this[name]?.jsonPrimitive?.int ?: error("missing '$name'")

fun JsonObject.requiredArray(name: String): JsonArray =
    this[name]?.jsonArray ?: error("missing '$name'")
