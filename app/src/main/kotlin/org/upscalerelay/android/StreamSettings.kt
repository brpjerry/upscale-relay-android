package org.upscalerelay.android

/*
 * The playback settings a relay stream is opened with, and whether the stream
 * on screen still carries the ones chosen now. A setting changed while an
 * open, reconnect or file advance is under way may or may not have been read
 * by it, and until it publishes, the session on screen may be the file being
 * left. So a change is decided only once that work has published its session
 * (RelayViewModel.applyStreamSettings), against what that session was opened
 * with.
 */

/** What a relay open reads from the settings; a change to any of them may restart the stream. */
internal data class StreamSettings(
    val model: String,
    val qualityTier: String,
    val fitMode: String,
    val resizeAlgorithm: String,
    val tenBitVideo: Boolean,
    val passthrough4k: Boolean,
)

/** The settings an open made now would read. */
internal val RelayUiState.streamSettings: StreamSettings
    get() = StreamSettings(selectedModel, qualityTier, fitMode, resizeAlgorithm, tenBitVideo, passthrough4k)

/**
 * What the stream an open that read these settings carries: the model and
 * tier the server resolved them to (an unadvertised choice falls back), as
 * the settings show them once the open has published ([settledChoice]).
 */
internal fun StreamSettings.carrying(chosenModel: String, qualityTier: String): StreamSettings =
    copy(model = chosenModel, qualityTier = qualityTier)

/**
 * A setting once an open that read [read] publishes [resolved], the value it
 * actually used: the server's resolution, unless the user has chosen again
 * while the open ran. That newer choice stays, and the restart that follows
 * the open applies it.
 */
internal fun <T> settledChoice(current: T, read: T, resolved: T): T = if (current == read) resolved else current

/**
 * Whether the relay stream on screen must restart to carry the settings
 * chosen now. [openedWith] is what it was opened with ([carrying]); null when
 * no stream is known to carry any: a restart, reconnect or advance has taken
 * it down and not published another. Model, 10-bit and passthrough changes
 * restart only a stream they change (modelChangeKeepsStream,
 * tenBitVideoChangesStream, passthrough4kChangesStream), judged against the
 * session on screen, which is the published one by the time this is asked.
 */
internal fun RelayUiState.settingsRestartNeeded(openedWith: StreamSettings?, decodesMain10: Boolean): Boolean {
    if (openedWith == null) return true
    val wanted = streamSettings
    return wanted.qualityTier != openedWith.qualityTier ||
        wanted.fitMode != openedWith.fitMode ||
        wanted.resizeAlgorithm != openedWith.resizeAlgorithm ||
        // The stand-in is judged against the model the stream was opened
        // for: passthrough chosen then has no 4K stand-in to keep.
        (wanted.model != openedWith.model && !copy(selectedModel = openedWith.model).modelChangeKeepsStream(wanted.model)) ||
        (wanted.tenBitVideo != openedWith.tenBitVideo && tenBitVideoChangesStream(decodesMain10)) ||
        (wanted.passthrough4k != openedWith.passthrough4k && passthrough4kChangesStream(wanted.passthrough4k))
}
