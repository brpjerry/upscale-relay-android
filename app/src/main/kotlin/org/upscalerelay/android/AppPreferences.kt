package org.upscalerelay.android

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.phaseThreeDataStore by preferencesDataStore(name = "phase_three")

data class AppPreferences(
    val host: String = "192.168.0.115",
    val port: Int = 8590,
    val autoConnect: Boolean = false,
    val autoResume: Boolean = true,
    val model: String = "",
    val qualityTier: String = "lossless-hevc",
    val fitMode: String = "fit",
    val resizeAlgorithm: String = "",
    val debandEnabled: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    val preferredSubtitle: String = "",
    val diagnosticsVisible: Boolean = false,
    val gesturesEnabled: Boolean = true,
    val displayResampleSync: Boolean = false,
    val interpolationEnabled: Boolean = false,
    val interpolationScaler: String = "oversample",
    val backgroundPlayback: Boolean = true,
    val fileLoggingEnabled: Boolean = false,
    val lastDestination: String = "",
    val recentPaths: List<String> = emptyList(),
    val recentLocalUris: List<String> = emptyList(),
    val recentLocalRootUris: List<String> = emptyList(),
    val playbackPositions: Map<String, Double> = emptyMap(),
)

class AppPreferencesStore(context: Context) {
    private val dataStore = context.applicationContext.phaseThreeDataStore

    val values: Flow<AppPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::decode)

    suspend fun setHost(value: String) = set(Keys.HOST, value)
    suspend fun setPort(value: Int) = set(Keys.PORT, value)
    suspend fun setAutoConnect(value: Boolean) = set(Keys.AUTO_CONNECT, value)
    suspend fun setAutoResume(value: Boolean) = set(Keys.AUTO_RESUME, value)
    suspend fun setModel(value: String) = set(Keys.MODEL, value)
    suspend fun setQualityTier(value: String) = set(Keys.QUALITY_TIER, value)
    suspend fun setFitMode(value: String) = set(Keys.FIT_MODE, value)
    suspend fun setResizeAlgorithm(value: String) = set(Keys.RESIZE_ALGORITHM, value)
    suspend fun setDebandEnabled(value: Boolean) = set(Keys.DEBAND_ENABLED, value)
    suspend fun setSubtitlesEnabled(value: Boolean) = set(Keys.SUBTITLES_ENABLED, value)
    suspend fun setPreferredSubtitle(value: String) = set(Keys.PREFERRED_SUBTITLE, value)
    suspend fun setDiagnosticsVisible(value: Boolean) = set(Keys.DIAGNOSTICS_VISIBLE, value)
    suspend fun setGesturesEnabled(value: Boolean) = set(Keys.GESTURES_ENABLED, value)
    suspend fun setDisplayResampleSync(value: Boolean) = set(Keys.DISPLAY_RESAMPLE_SYNC, value)
    suspend fun setInterpolationEnabled(value: Boolean) = set(Keys.INTERPOLATION_ENABLED, value)
    suspend fun setInterpolationScaler(value: String) = set(Keys.INTERPOLATION_SCALER, value)
    suspend fun setBackgroundPlayback(value: Boolean) = set(Keys.BACKGROUND_PLAYBACK, value)
    suspend fun setFileLoggingEnabled(value: Boolean) = set(Keys.FILE_LOGGING, value)
    suspend fun setLastDestination(value: String) = set(Keys.LAST_DESTINATION, value)

    suspend fun addRecent(path: String) {
        dataStore.edit { preferences ->
            val current = decodeRecents(preferences[Keys.RECENTS].orEmpty())
            preferences[Keys.RECENTS] = updateRecentPaths(current, path).joinToString("\n")
        }
    }

    suspend fun addRecentLocalUri(uri: String) {
        dataStore.edit { preferences ->
            val current = decodeRecents(preferences[Keys.LOCAL_RECENTS].orEmpty())
            preferences[Keys.LOCAL_RECENTS] = updateRecentPaths(current, uri).joinToString("\n")
        }
    }

    suspend fun addRecentLocalRootUri(uri: String) {
        dataStore.edit { preferences ->
            val current = decodeRecents(preferences[Keys.LOCAL_ROOT_RECENTS].orEmpty())
            preferences[Keys.LOCAL_ROOT_RECENTS] = updateRecentPaths(current, uri).joinToString("\n")
        }
    }

    suspend fun clearRecents() {
        dataStore.edit { it.remove(Keys.RECENTS) }
    }

    /** Stores the resume point for a file; most recent first, bounded. */
    suspend fun setPlaybackPosition(key: String, seconds: Double) {
        dataStore.edit { preferences ->
            val current = decodePositions(preferences[Keys.PLAYBACK_POSITIONS].orEmpty())
            val next = linkedMapOf(key to seconds)
            current.forEach { (k, v) -> if (k != key && next.size < MAX_POSITIONS) next[k] = v }
            preferences[Keys.PLAYBACK_POSITIONS] = encodePositions(next)
        }
    }

    suspend fun clearPlaybackPosition(key: String) {
        dataStore.edit { preferences ->
            val current = decodePositions(preferences[Keys.PLAYBACK_POSITIONS].orEmpty())
            if (key in current) {
                preferences[Keys.PLAYBACK_POSITIONS] = encodePositions(current - key)
            }
        }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    private fun decode(preferences: Preferences) = AppPreferences(
        host = preferences[Keys.HOST] ?: "192.168.0.115",
        port = preferences[Keys.PORT] ?: 8590,
        autoConnect = preferences[Keys.AUTO_CONNECT] ?: false,
        autoResume = preferences[Keys.AUTO_RESUME] ?: true,
        model = preferences[Keys.MODEL].orEmpty(),
        qualityTier = when (val tier = preferences[Keys.QUALITY_TIER] ?: "lossless-hevc") {
            "visually-lossless" -> "hevc-qp18"
            else -> tier
        },
        fitMode = preferences[Keys.FIT_MODE] ?: "fit",
        resizeAlgorithm = preferences[Keys.RESIZE_ALGORITHM].orEmpty(),
        debandEnabled = preferences[Keys.DEBAND_ENABLED] ?: false,
        subtitlesEnabled = preferences[Keys.SUBTITLES_ENABLED] ?: true,
        preferredSubtitle = preferences[Keys.PREFERRED_SUBTITLE].orEmpty(),
        diagnosticsVisible = preferences[Keys.DIAGNOSTICS_VISIBLE] ?: false,
        gesturesEnabled = preferences[Keys.GESTURES_ENABLED] ?: true,
        displayResampleSync = preferences[Keys.DISPLAY_RESAMPLE_SYNC] ?: false,
        interpolationEnabled = preferences[Keys.INTERPOLATION_ENABLED] ?: false,
        interpolationScaler = preferences[Keys.INTERPOLATION_SCALER] ?: "oversample",
        backgroundPlayback = preferences[Keys.BACKGROUND_PLAYBACK] ?: true,
        fileLoggingEnabled = preferences[Keys.FILE_LOGGING] ?: false,
        lastDestination = preferences[Keys.LAST_DESTINATION].orEmpty(),
        recentPaths = decodeRecents(preferences[Keys.RECENTS].orEmpty()),
        recentLocalUris = decodeRecents(preferences[Keys.LOCAL_RECENTS].orEmpty()),
        recentLocalRootUris = decodeRecents(preferences[Keys.LOCAL_ROOT_RECENTS].orEmpty()),
        playbackPositions = decodePositions(preferences[Keys.PLAYBACK_POSITIONS].orEmpty()),
    )

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val AUTO_RESUME = booleanPreferencesKey("auto_resume")
        val MODEL = stringPreferencesKey("model")
        val QUALITY_TIER = stringPreferencesKey("quality_tier")
        val FIT_MODE = stringPreferencesKey("fit_mode")
        val RESIZE_ALGORITHM = stringPreferencesKey("resize_algorithm")
        val DEBAND_ENABLED = booleanPreferencesKey("deband_enabled")
        val SUBTITLES_ENABLED = booleanPreferencesKey("subtitles_enabled")
        val PREFERRED_SUBTITLE = stringPreferencesKey("preferred_subtitle")
        val DIAGNOSTICS_VISIBLE = booleanPreferencesKey("diagnostics_visible")
        val GESTURES_ENABLED = booleanPreferencesKey("gestures_enabled")
        val DISPLAY_RESAMPLE_SYNC = booleanPreferencesKey("display_resample_sync")
        val INTERPOLATION_ENABLED = booleanPreferencesKey("interpolation_enabled")
        val INTERPOLATION_SCALER = stringPreferencesKey("interpolation_scaler")
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val FILE_LOGGING = booleanPreferencesKey("file_logging_enabled")
        val LAST_DESTINATION = stringPreferencesKey("last_destination")
        val RECENTS = stringPreferencesKey("recent_paths")
        val LOCAL_RECENTS = stringPreferencesKey("recent_local_uris")
        val LOCAL_ROOT_RECENTS = stringPreferencesKey("recent_local_root_uris")
        val PLAYBACK_POSITIONS = stringPreferencesKey("playback_positions")
    }
}

internal fun decodeRecents(value: String): List<String> =
    value.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_RECENTS).toList()

internal fun updateRecentPaths(current: List<String>, path: String): List<String> =
    (listOf(path) + current.filterNot { it == path }).take(MAX_RECENTS)

/** One "key<US>seconds" entry per line, insertion order = most recent first. */
internal fun decodePositions(value: String): Map<String, Double> = buildMap {
    value.lineSequence().forEach { line ->
        val separator = line.lastIndexOf('\u001F')
        if (separator <= 0) return@forEach
        val seconds = line.substring(separator + 1).toDoubleOrNull() ?: return@forEach
        if (size < MAX_POSITIONS) put(line.substring(0, separator), seconds)
    }
}

internal fun encodePositions(value: Map<String, Double>) =
    value.entries.joinToString("\n") { (k, v) -> "$k\u001F$v" }

internal const val MAX_RECENTS = 20
internal const val MAX_POSITIONS = 50
