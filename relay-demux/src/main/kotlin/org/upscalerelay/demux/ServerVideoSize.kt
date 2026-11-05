package org.upscalerelay.demux

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.suspendCancellableCoroutine
import org.upscalerelay.client.HttpRangeReader
import org.upscalerelay.protocol.VideoSize
import kotlin.concurrent.thread

/** What [ServerVideoSize.probe] read, and what reading it cost. */
data class ProbedVideoSize(val size: VideoSize?, val bytesRead: Long, val requests: Int)

/**
 * The stored size of a server-library file's first video track, read through
 * its /media URL by MediaExtractor, the demuxer that reads an uplink's own
 * size (AndroidMediaSource). Only what the extractor reads to describe the
 * tracks crosses the network: [HttpRangeReader] fetches it in chunks, and an
 * element the extractor skips (a Matroska file's fonts) is never fetched.
 */
object ServerVideoSize {
    /**
     * Reads on a thread of its own. A blocked read ignores coroutine
     * cancellation, so cancelling closes the reader instead, which fails that
     * read and every later one, and the extractor gives up. Throws when the
     * extractor cannot open the file.
     */
    suspend fun probe(mediaUrl: String): ProbedVideoSize = suspendCancellableCoroutine { continuation ->
        val reader = HttpRangeReader(mediaUrl)
        continuation.invokeOnCancellation { reader.close() }
        thread(name = "relay-android-size-probe", isDaemon = true) {
            val result = runCatching { read(reader) }
            reader.close()
            // A resume after cancellation is ignored.
            continuation.resumeWith(result)
        }
    }

    private fun read(reader: HttpRangeReader): ProbedVideoSize {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(RangeDataSource(reader))
            val video = (0 until extractor.trackCount).asSequence()
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val size = video?.let { VideoSize.of(it.int(MediaFormat.KEY_WIDTH), it.int(MediaFormat.KEY_HEIGHT)) }
            return ProbedVideoSize(size, reader.bytesFetched, reader.requestCount)
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    /** The extractor's view of [reader]; a failed read throws, which the platform reports as an error. */
    private class RangeDataSource(private val reader: HttpRangeReader) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            reader.readAt(position, buffer, offset, size)

        override fun getSize(): Long = reader.size()

        override fun close() = reader.close()
    }
}
