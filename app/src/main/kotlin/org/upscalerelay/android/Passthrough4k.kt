package org.upscalerelay.android

import org.upscalerelay.client.PASSTHROUGH_MODEL
import org.upscalerelay.client.PlaybackEndpoint
import org.upscalerelay.client.is4kOrHigher

/*
 * "Use passthrough for 4K or higher resolution videos" in the app: the name
 * the player gives the model, and which toggles restart the stream. Which
 * model a session opens with is ModelChoice's, where the controller opens it.
 */

/** The model a session was opened with, as the player names it: "passthrough (4K source)" when the option swapped it in. */
internal fun PlaybackEndpoint.modelDescription(): String =
    if (passthroughFor4k) "$PASSTHROUGH_MODEL (4K source)" else model

/**
 * Whether turning the option to [enabled] changes the relay stream playing
 * now. On: a stream not already on passthrough whose source is 4K or larger,
 * or a library file opened with the option off, whose size was never read.
 * Off: a stream passthrough opened in place of the chosen model. Neither
 * when passthrough is the chosen model, or the original file plays directly.
 */
internal fun RelayUiState.passthrough4kChangesStream(enabled: Boolean): Boolean {
    val session = relaySession ?: return false
    if (selectedModel == PASSTHROUGH_MODEL) return false
    return if (enabled) {
        capabilities?.models.orEmpty().any { it.name == PASSTHROUGH_MODEL } &&
            session.model != PASSTHROUGH_MODEL &&
            session.sourceSize?.is4kOrHigher() != false
    } else {
        session.model == PASSTHROUGH_MODEL
    }
}
