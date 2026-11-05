package org.upscalerelay.android

import org.upscalerelay.client.PASSTHROUGH_MODEL
import org.upscalerelay.client.PlaybackEndpoint
import org.upscalerelay.client.is4kOrHigher
import org.upscalerelay.protocol.VideoSize

/*
 * "Use passthrough for 4K or higher resolution videos" in the app: the name
 * the player gives the model, which changes restart the stream, and which
 * size a restart may reuse. Which model a session opens with is
 * ModelChoice's, where the controller opens it.
 */

/** The model a session was opened with, as the player names it: "passthrough (4K source)" when the option swapped it in. */
internal fun PlaybackEndpoint.modelDescription(): String =
    if (passthroughFor4k) "$PASSTHROUGH_MODEL (4K source)" else model

/**
 * Whether turning the option to [enabled] changes the relay stream playing
 * now. On: a stream not already on passthrough whose source is 4K or larger,
 * or a library file opened with the option off, whose size was never read.
 * (A local file's size is its demuxer's, so an unknown one stays unknown.)
 * Off: a stream passthrough opened in place of the chosen model. Neither
 * when passthrough is the chosen model, or the original file plays directly.
 */
internal fun RelayUiState.passthrough4kChangesStream(enabled: Boolean): Boolean {
    val session = relaySession ?: return false
    if (selectedModel == PASSTHROUGH_MODEL) return false
    return if (enabled) {
        capabilities?.models.orEmpty().any { it.name == PASSTHROUGH_MODEL } &&
            session.model != PASSTHROUGH_MODEL &&
            (session.sourceSize?.is4kOrHigher() ?: !localPlayback)
    } else {
        session.model == PASSTHROUGH_MODEL
    }
}

/**
 * Whether choosing [next] as the model leaves the relay stream as it is: a
 * 4K source the option put on passthrough in place of one upscaling model
 * stays on it under any other. Choosing passthrough itself still restarts,
 * so the player stops calling it a stand-in.
 */
internal fun RelayUiState.modelChangeKeepsStream(next: String): Boolean {
    val session = relaySession ?: return false
    return passthrough4k && next != PASSTHROUGH_MODEL && selectedModel != PASSTHROUGH_MODEL &&
        session.model == PASSTHROUGH_MODEL && session.sourceSize?.is4kOrHigher() == true
}

/**
 * The size the last relay open read, and the file it read it for. A restart
 * of that same file (a setting change, a resume after the network returns)
 * reuses it instead of reading the header again. Any other file probes:
 * auto-play and next/previous move to the next file before its restart
 * runs, while the session on screen still holds the old file's size.
 */
internal class SourceSizeMemo<K : Any> {
    private var entry: Pair<K, VideoSize>? = null

    /** Records what the open of [source] read; null forgets, as an open that read nothing knows nothing. */
    fun remember(source: K, size: VideoSize?) {
        entry = size?.let { source to it }
    }

    /** The size read for [source], if the last open was of it. */
    fun sizeOf(source: K): VideoSize? = entry?.takeIf { it.first == source }?.second
}
