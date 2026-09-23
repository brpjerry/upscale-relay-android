package org.upscalerelay.demux

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import org.upscalerelay.client.UplinkAccessUnit
import org.upscalerelay.client.UplinkMediaSource
import org.upscalerelay.client.UplinkPacketReader
import org.upscalerelay.client.UplinkVideoInfo
import org.upscalerelay.protocol.ChapterInfo
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A fresh MediaExtractor and file descriptor are owned by every seek epoch. */
class AndroidMediaSource private constructor(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val videoInfo: UplinkVideoInfo,
    private val videoTrack: Int,
) : UplinkMediaSource {
    private val closed = AtomicBoolean(false)
    private val readers = mutableSetOf<ExtractorPacketReader>()

    override fun openPacketReader(fromPts: Long?): UplinkPacketReader {
        val reader = ExtractorPacketReader(resolver, uri, videoTrack, videoInfo.codec, fromPts) {
            synchronized(readers) { readers.remove(it) }
        }
        synchronized(readers) {
            check(!closed.get()) { "local media source is closed" }
            readers += reader
        }
        try {
            reader.initialize()
            return reader
        } catch (error: Throwable) {
            reader.close()
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(readers) { readers.toList() }.forEach { it.close() }
    }

    companion object {
        fun open(context: Context, uri: Uri): AndroidMediaSource {
            val resolver = (context.applicationContext ?: context).contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "local-video"
            val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r")) {
                "Cannot open the selected document"
            }
            descriptor.use { pfd ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                            ?.startsWith("video/") == true
                    } ?: error("The selected document has no video track")
                    val format = extractor.getTrackFormat(videoTrack)
                    val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
                    val codec = codecName(mime)
                    val durationUs = format.longOrNull(MediaFormat.KEY_DURATION)
                    val fps = format.intOrNull(MediaFormat.KEY_FRAME_RATE)
                    val extradata = (0..3).mapNotNull { index ->
                        format.byteBufferOrNull("csd-$index")
                    }.fold(ByteArray(0)) { left, right -> left + right }.takeIf(ByteArray::isNotEmpty)
                    val chapters = readChapters(resolver, uri)
                    val hasAudio = (0 until extractor.trackCount).any { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    }
                    return AndroidMediaSource(
                        resolver = resolver,
                        uri = uri,
                        videoTrack = videoTrack,
                        videoInfo = UplinkVideoInfo(
                            name = name,
                            codec = codec,
                            extradata = extradata,
                            width = format.getInteger(MediaFormat.KEY_WIDTH),
                            height = format.getInteger(MediaFormat.KEY_HEIGHT),
                            timeBaseNumerator = 1,
                            timeBaseDenominator = 1_000_000,
                            averageRateNumerator = fps,
                            averageRateDenominator = fps?.let { 1 },
                            durationSeconds = durationUs?.div(1_000_000.0),
                            chapters = chapters,
                            // Extractor can omit formats understood by mpv. Positive
                            // presence is useful, but absence cannot certify that
                            // the original has no audio or subtitle tracks.
                            sourceHasAudio = if (hasAudio) true else null,
                            sourceHasAuxiliary = if (hasAudio) true else null,
                        ),
                    )
                } finally {
                    extractor.release()
                }
            }
        }

        /**
         * Best-effort Matroska chapter extraction (MediaExtractor has no
         * chapter API). Non-MKV documents fail the EBML magic immediately and
         * return an empty list.
         */
        private fun readChapters(resolver: ContentResolver, uri: Uri): List<ChapterInfo> =
            runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { stream ->
                        MatroskaChapters.parse(stream.channel)
                    }
                }
            }.getOrNull().orEmpty()

        private fun codecName(mime: String): String = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc"
            MediaFormat.MIMETYPE_VIDEO_AVC -> "h264"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "av1"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "vp9"
            MediaFormat.MIMETYPE_VIDEO_MPEG2 -> "mpeg2video"
            MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "mpeg4"
            else -> error("Unsupported local video codec: $mime")
        }
    }
}

private class ExtractorPacketReader(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val videoTrack: Int,
    private val codec: String,
    private val fromPts: Long?,
    private val onClosed: (ExtractorPacketReader) -> Unit,
) : UplinkPacketReader {
    private val lock = ReentrantLock()
    private val cancellation = CancellationSignal()
    @Volatile private var descriptor: android.os.ParcelFileDescriptor? = null
    private var extractor: MediaExtractor? = null
    private val closed = AtomicBoolean(false)
    private var buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_BYTES)

    fun initialize() = lock.withLock {
        try {
            cancellation.throwIfCanceled()
            val opened = requireNotNull(resolver.openFileDescriptor(uri, "r", cancellation))
            descriptor = opened
            cancellation.throwIfCanceled()
            val demuxer = MediaExtractor().also { extractor = it }
            demuxer.setDataSource(opened.fileDescriptor)
            cancellation.throwIfCanceled()
            demuxer.selectTrack(videoTrack)
            if (fromPts != null && fromPts > 0) {
                demuxer.seekTo(fromPts, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            cancellation.throwIfCanceled()
        } finally {
            if (closed.get()) releaseExtractor()
        }
    }

    override fun read(): UplinkAccessUnit? = lock.withLock {
        if (closed.get()) return null
        val extractor = requireNotNull(extractor)
        try {
            if (extractor.sampleTime < 0) return null
            val sampleSize = extractor.sampleSize
            if (sampleSize > buffer.capacity()) {
                require(sampleSize <= MAX_ACCESS_UNIT_BYTES) { "video access unit is too large: $sampleSize" }
                buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
            }
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return null
            val payload = ByteArray(size)
            buffer.position(0)
            buffer.get(payload)
            val pts = extractor.sampleTime
            val keyframe = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            extractor.advance()
            UplinkAccessUnit(normalizeNalUnits(payload, codec), pts, keyframe)
        } finally {
            if (closed.get()) releaseExtractor()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancellation.cancel()
        runCatching { descriptor?.close() }
        // A provider read must not hold close() hostage. Its owner releases
        // the extractor when the interrupted native operation returns.
        if (lock.tryLock()) {
            try { releaseExtractor() } finally { lock.unlock() }
        }
        onClosed(this)
    }

    private fun releaseExtractor() {
        extractor?.release()
        extractor = null
        descriptor?.close()
        descriptor = null
    }

    companion object {
        private const val DEFAULT_BUFFER_BYTES = 4 * 1024 * 1024
        private const val MAX_ACCESS_UNIT_BYTES = 64 * 1024 * 1024
    }
}

/** Convert common four-byte length-prefixed AVC/HEVC samples to Annex B. */
internal fun normalizeNalUnits(payload: ByteArray, codec: String): ByteArray {
    if ((codec != "h264" && codec != "hevc") || payload.size < 4) return payload
    if (payload.startsWithStartCode()) return payload
    var offset = 0
    while (offset + 4 <= payload.size) {
        val length = payload.nalLengthAt(offset)
        if (length <= 0 || length > payload.size - offset - 4) return payload
        offset += 4 + length
    }
    if (offset != payload.size) return payload
    // Four-byte lengths and Annex B start codes have identical widths. One
    // fixed-size copy avoids a growing stream plus a second full-size copy.
    val output = payload.copyOf()
    offset = 0
    while (offset < payload.size) {
        val length = payload.nalLengthAt(offset)
        output[offset] = 0
        output[offset + 1] = 0
        output[offset + 2] = 0
        output[offset + 3] = 1
        offset += 4 + length
    }
    return output
}

private fun ByteArray.nalLengthAt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)

private fun ByteArray.startsWithStartCode(): Boolean =
    (size >= 3 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 1.toByte()) ||
        (size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() &&
            this[2] == 0.toByte() && this[3] == 1.toByte())

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null

private fun MediaFormat.byteBufferOrNull(key: String): ByteArray? {
    if (!containsKey(key)) return null
    val source = getByteBuffer(key)?.duplicate() ?: return null
    val bytes = ByteArray(source.remaining())
    source.get(bytes)
    return bytes
}
