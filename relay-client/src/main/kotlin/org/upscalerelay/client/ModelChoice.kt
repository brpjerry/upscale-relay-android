package org.upscalerelay.client

import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.VideoSize

/*
 * "Use passthrough for 4K or higher resolution videos", as the desktop client
 * has it (server repo, relay_client_core SessionConfig.passthrough_4k). A 2x
 * model on a 4K source runs well below realtime (about 123 ms per frame on
 * the server's RTX 5090) and its 8K output is scaled down to the display
 * anyway, so such a source opens with the passthrough model instead.
 */

/** The server's model that streams the source without upscaling it. */
const val PASSTHROUGH_MODEL = "passthrough"

/** 3840 wide or 2160 tall and up: UHD, DCI 4K and scope crops of either. */
fun VideoSize.is4kOrHigher(): Boolean = width >= 3840 || height >= 2160

/**
 * Reads a server-library file's stored video size through its /media URL
 * before the session opens. Null when the file has no video track the
 * reader understands; a failure may also throw. Either way the session opens
 * with the chosen model. Cancelling the call must end the read promptly.
 */
fun interface SourceSizeProbe {
    suspend fun videoSize(mediaUrl: String): VideoSize?
}

/**
 * The model a session opens with. [chosen] is the user's model as the server
 * advertises it; [sent] is what open_session carries: [chosen], or
 * [PASSTHROUGH_MODEL] in its place for a source that [is4kOrHigher].
 * [sourceSize] is the size that decided it, when one was read.
 */
data class ModelChoice(val chosen: String, val sent: String, val sourceSize: VideoSize?) {
    /** Passthrough replaced another model because the source is 4K or larger. */
    val passthroughFor4k: Boolean
        get() = sent == PASSTHROUGH_MODEL && chosen != PASSTHROUGH_MODEL

    companion object {
        /** [requested] when the server advertises it, else the server's first upscaling model. */
        fun chosenModel(caps: Capabilities, requested: String?): String =
            requested?.takeIf { name -> caps.models.any { it.name == name } } ?: caps.phaseOneModel

        /**
         * Whether the source's size can change the model at all, and so
         * whether a server file's is worth reading: the option is on, the
         * chosen model is not passthrough already, and the server has one.
         */
        fun sizeMatters(caps: Capabilities, chosen: String, passthrough4k: Boolean): Boolean =
            passthrough4k && chosen != PASSTHROUGH_MODEL && caps.models.any { it.name == PASSTHROUGH_MODEL }

        /**
         * [chosen], or passthrough for a source of [sourceSize] that is 4K or
         * larger when [sizeMatters]. An unknown size (a probe that failed)
         * keeps the chosen model.
         */
        fun decide(caps: Capabilities, chosen: String, passthrough4k: Boolean, sourceSize: VideoSize?): ModelChoice {
            val swap = sizeMatters(caps, chosen, passthrough4k) && sourceSize?.is4kOrHigher() == true
            return ModelChoice(chosen, if (swap) PASSTHROUGH_MODEL else chosen, sourceSize)
        }
    }
}
