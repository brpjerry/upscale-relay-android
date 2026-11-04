package org.upscalerelay.player.mpv

import android.content.Context
import android.util.Log
import java.io.File

/**
 * HDR output spike, prototype branch only: `name=value` lines from
 * `files/mpv-spike.conf`, read once when the engine initializes.
 *
 * Plain names are mpv options, applied after the engine's own. `spike.*`
 * names steer the prototype itself:
 * - `spike.display=pq:540:203` names that display to the server instead of SDR.
 * - `spike.dataspace=<ADataSpace>` tags mpv's Surface after each VO init.
 */
object SpikeConfig {
    private const val TAG = "RelaySpike"

    @Volatile
    var values: Map<String, String> = emptyMap()
        private set

    fun reload(context: Context): Map<String, String> {
        val file = File(context.filesDir, "mpv-spike.conf")
        values = if (!file.isFile) emptyMap() else file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        Log.i(TAG, "spike options: $values")
        return values
    }

    val mpvOptions: Map<String, String> get() = values.filterKeys { !it.startsWith("spike.") }
}
