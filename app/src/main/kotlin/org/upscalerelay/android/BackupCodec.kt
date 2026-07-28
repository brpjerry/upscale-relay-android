package org.upscalerelay.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.upscalerelay.client.RelaySessionController
import org.upscalerelay.player.mpv.MpvPlayerEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Human-readable JSON for everything the app persists: connection details,
 * playback and player preferences, the recent lists, and the watch history.
 *
 * The file is meant to be opened and edited by hand, so it is pretty-printed,
 * grouped the way the settings screen is, and carries an ISO timestamp beside
 * every epoch-millis field. Decoding is correspondingly forgiving: a section
 * or key that is absent keeps the value the device already has, and anything
 * out of range falls back to the same default the store would use, so a
 * partial or lightly mangled file restores what it can instead of failing.
 */
object BackupCodec {
    const val FORMAT = "upscale-relay-android-backup"
    const val VERSION = 1

    /** Refuse implausibly large files rather than reading them into memory. */
    const val MAX_BYTES = 8 * 1024 * 1024

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun suggestedFileName(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "upscale-relay-backup-${fileStamp.format(at.atZone(zone))}.json"

    fun encode(
        preferences: AppPreferences,
        appVersion: String,
        exportedAt: Instant,
    ): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", exportedAt.toString())
            put("appVersion", appVersion)
            putJsonObject("connection") {
                put("host", preferences.host)
                put("port", preferences.port)
                put("autoConnect", preferences.autoConnect)
                put("autoResume", preferences.autoResume)
            }
            putJsonObject("playbackDefaults") {
                put("model", preferences.model)
                put("qualityTier", preferences.qualityTier)
                put("fitMode", preferences.fitMode)
                put("resizeAlgorithm", preferences.resizeAlgorithm)
                put("debandEnabled", preferences.debandEnabled)
                put("subtitlesEnabled", preferences.subtitlesEnabled)
                put("preferredSubtitle", preferences.preferredSubtitle)
            }
            putJsonObject("player") {
                put("gesturesEnabled", preferences.gesturesEnabled)
                put("diagnosticsVisible", preferences.diagnosticsVisible)
                put("backgroundPlayback", preferences.backgroundPlayback)
                put("autoPlayNext", preferences.autoPlayNext)
                put("fileLoggingEnabled", preferences.fileLoggingEnabled)
                put("displayResampleSync", preferences.displayResampleSync)
                put("interpolationEnabled", preferences.interpolationEnabled)
                put("interpolationScaler", preferences.interpolationScaler)
                put("playbackHistoryLimit", preferences.playbackHistoryLimit)
            }
            putJsonObject("library") {
                put("librarySort", preferences.librarySort)
                put("lastDestination", preferences.lastDestination)
                put("lastLibraryPath", preferences.lastLibraryPath)
                putJsonArray("recentServerPaths") {
                    preferences.recentPaths.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("recentLocalUris") {
                    preferences.recentLocalUris.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("recentLocalFolderUris") {
                    preferences.recentLocalRootUris.forEach { add(JsonPrimitive(it)) }
                }
            }
            // An array, not an object: the order is the history order (most
            // recently played first) and trimming to the limit depends on it.
            putJsonArray("watchHistory") {
                preferences.playbackPositions.forEach { (key, progress) ->
                    addJsonObject {
                        put("key", key)
                        put("positionSeconds", progress.positionSeconds)
                        put("durationSeconds", progress.durationSeconds)
                        put("lastPlayedAtMillis", progress.lastPlayedAtMillis)
                        if (progress.lastPlayedAtMillis > 0) {
                            put(
                                "lastPlayedAt",
                                Instant.ofEpochMilli(progress.lastPlayedAtMillis).toString(),
                            )
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Reads [text] over the top of [current]. Throws [IllegalArgumentException]
     * with a message fit for the UI when the file is not a backup this build
     * understands.
     */
    fun decode(text: String, current: AppPreferences): AppPreferences {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("That file is not valid JSON.")
        require(root.string("format") == FORMAT) {
            "That file is not an Upscale Relay backup."
        }
        val version = root.int("version") ?: 0
        require(version <= VERSION) {
            "That backup was written by a newer version of the app (format $version)."
        }

        val connection = root.obj("connection")
        val playback = root.obj("playbackDefaults")
        val player = root.obj("player")
        val library = root.obj("library")

        val historyLimit = (player?.int("playbackHistoryLimit") ?: current.playbackHistoryLimit)
            .coerceIn(1, MAX_POSITIONS_LIMIT)
        return current.copy(
            host = connection?.string("host")?.trim()?.takeIf(String::isNotEmpty) ?: current.host,
            port = connection?.int("port")?.takeIf { it in 1..65535 } ?: current.port,
            autoConnect = connection?.bool("autoConnect") ?: current.autoConnect,
            autoResume = connection?.bool("autoResume") ?: current.autoResume,
            model = playback?.string("model") ?: current.model,
            qualityTier = playback?.string("qualityTier")?.takeIf(String::isNotEmpty)
                ?: current.qualityTier,
            fitMode = playback?.string("fitMode")?.takeIf { it in RelaySessionController.FIT_MODES }
                ?: current.fitMode,
            resizeAlgorithm = playback?.string("resizeAlgorithm") ?: current.resizeAlgorithm,
            debandEnabled = playback?.bool("debandEnabled") ?: current.debandEnabled,
            subtitlesEnabled = playback?.bool("subtitlesEnabled") ?: current.subtitlesEnabled,
            preferredSubtitle = playback?.string("preferredSubtitle") ?: current.preferredSubtitle,
            gesturesEnabled = player?.bool("gesturesEnabled") ?: current.gesturesEnabled,
            diagnosticsVisible = player?.bool("diagnosticsVisible") ?: current.diagnosticsVisible,
            backgroundPlayback = player?.bool("backgroundPlayback") ?: current.backgroundPlayback,
            autoPlayNext = player?.bool("autoPlayNext") ?: current.autoPlayNext,
            fileLoggingEnabled = player?.bool("fileLoggingEnabled") ?: current.fileLoggingEnabled,
            displayResampleSync = player?.bool("displayResampleSync") ?: current.displayResampleSync,
            interpolationEnabled = player?.bool("interpolationEnabled")
                ?: current.interpolationEnabled,
            interpolationScaler = player?.string("interpolationScaler")
                ?.takeIf { it in MpvPlayerEngine.INTERPOLATION_SCALERS }
                ?: current.interpolationScaler,
            playbackHistoryLimit = historyLimit,
            librarySort = library?.string("librarySort")
                ?.takeIf { name -> LibrarySort.entries.any { it.name == name } }
                ?: current.librarySort,
            // Empty is the store's "no destination chosen yet" sentinel and has
            // to survive a round trip like any real value.
            lastDestination = library?.string("lastDestination")
                ?.takeIf { name ->
                    name.isEmpty() || TabletDestination.entries.any { it.name == name }
                }
                ?: current.lastDestination,
            lastLibraryPath = library?.string("lastLibraryPath") ?: current.lastLibraryPath,
            recentPaths = library?.strings("recentServerPaths")?.take(MAX_RECENTS)
                ?: current.recentPaths,
            recentLocalUris = library?.strings("recentLocalUris")?.take(MAX_RECENTS)
                ?: current.recentLocalUris,
            recentLocalRootUris = library?.strings("recentLocalFolderUris")?.take(MAX_RECENTS)
                ?: current.recentLocalRootUris,
            playbackPositions = decodeHistory(root["watchHistory"], historyLimit)
                ?: current.playbackPositions,
        )
    }

    private fun decodeHistory(
        element: kotlinx.serialization.json.JsonElement?,
        limit: Int,
    ): Map<String, PlaybackProgress>? {
        val entries = element as? JsonArray ?: return null
        return buildMap {
            for (entry in entries) {
                if (size >= limit) break
                val row = entry as? JsonObject ?: continue
                val key = row.string("key")?.takeIf(String::isNotEmpty) ?: continue
                // The separator is the store's record delimiter; a key holding
                // one would corrupt every later line on the next write.
                if ('\u001F' in key || '\n' in key) continue
                val position = row.double("positionSeconds") ?: continue
                if (!position.isFinite() || position < 0) continue
                val duration = row.double("durationSeconds")?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
                val playedAt = row.long("lastPlayedAtMillis")?.takeIf { it >= 0 } ?: 0L
                put(key, PlaybackProgress(position, duration, playedAt))
            }
        }
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.strings(name: String): List<String>? =
        (this[name] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)
        }
}
