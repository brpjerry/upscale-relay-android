package org.upscalerelay.client

import org.upscalerelay.protocol.ChapterInfo
import java.io.Closeable

/** Encoded local-video metadata required by protocol v1 open_session. */
data class UplinkVideoInfo(
    val name: String,
    val codec: String,
    val extradata: ByteArray?,
    val width: Int,
    val height: Int,
    val timeBaseNumerator: Int,
    val timeBaseDenominator: Int,
    val averageRateNumerator: Int?,
    val averageRateDenominator: Int?,
    val durationSeconds: Double?,
    // Sent as open_session.file.chapters; the server echoes them back in
    // session_opened so both playback sources read chapters the same way.
    val chapters: List<ChapterInfo> = emptyList(),
)

data class UplinkAccessUnit(
    val payload: ByteArray,
    val pts: Long,
    val keyframe: Boolean,
)

/**
 * Each reader owns an independent demuxer/descriptor generation. Closing a
 * superseded reader must make its next read finish promptly and can never move
 * the read position of a newer seek generation.
 */
interface UplinkPacketReader : Closeable {
    fun read(): UplinkAccessUnit?
}

interface UplinkMediaSource : Closeable {
    val videoInfo: UplinkVideoInfo
    fun openPacketReader(fromPts: Long?): UplinkPacketReader
}
