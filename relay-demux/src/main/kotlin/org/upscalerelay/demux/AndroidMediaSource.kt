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
import org.upscalerelay.protocol.DolbyVisionConfig
import org.upscalerelay.protocol.StaticHdrMetadata
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
                    fun mimeOf(index: Int) = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    val videoTracks = (0 until extractor.trackCount).filter { mimeOf(it)?.startsWith("video/") == true }
                    if (videoTracks.isEmpty()) error("The selected document has no video track")
                    // A Dolby Vision MP4 lists its samples twice: as
                    // video/dolby-vision, with the configuration record in
                    // csd-2, and as its base layer's codec (verified on the
                    // Tab S9 Ultra). The server decodes the base layer and
                    // takes the RPU from the samples, so that track is read.
                    val dolbyVisionTrack = videoTracks.firstOrNull {
                        mimeOf(it) == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
                    }
                    val dolbyVisionFormat = dolbyVisionTrack?.let(extractor::getTrackFormat)
                    val videoTrack = videoTracks.firstOrNull { codecName(mimeOf(it)) != null }
                        ?: dolbyVisionTrack
                        ?: error("Unsupported local video codec: ${mimeOf(videoTracks.first())}")
                    val format = extractor.getTrackFormat(videoTrack)
                    val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
                    val dolbyVision = dolbyVisionFormat?.byteBufferOrNull("csd-2")?.let(DolbyVisionConfig::fromRecord)
                        ?: readMatroskaDolbyVision(resolver, uri)
                    val codec = codecName(mime)
                        ?: dolbyVision?.let { dolbyVisionBaseCodec(it.profile) }
                        ?: dolbyVisionBaseCodecOfPlatformProfile(format.intOrNull(MediaFormat.KEY_PROFILE))
                    val durationUs = format.longOrNull(MediaFormat.KEY_DURATION)
                    val fps = format.intOrNull(MediaFormat.KEY_FRAME_RATE)
                    val extradata = (0..3).mapNotNull { index ->
                        format.byteBufferOrNull("csd-$index")
                    }.fold(ByteArray(0)) { left, right -> left + right }.takeIf(ByteArray::isNotEmpty)
                    val chapters = readChapters(resolver, uri)
                    // MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH/HEIGHT (API 30)
                    // arrive for MP4; Matroska's sit in its Video element.
                    val sampleAspect = format.intOrNull("sar-width")?.let { width ->
                        format.intOrNull("sar-height")?.let { height -> width to height }
                    } ?: readMatroskaSampleAspect(resolver, uri)
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
                            sampleAspectNumerator = sampleAspect?.first,
                            sampleAspectDenominator = sampleAspect?.second,
                            color = platformColor(
                                standard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD),
                                transfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                                range = format.intOrNull(MediaFormat.KEY_COLOR_RANGE),
                            ),
                            bitDepth = sourceBitDepth(
                                bitsPerSample = format.intOrNull(KEY_BITS_PER_SAMPLE),
                                codec = codec,
                                profile = if (mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) null
                                else format.intOrNull(MediaFormat.KEY_PROFILE),
                            ),
                            staticHdr = staticHdrFromCta861(
                                format.byteBufferOrNull(MediaFormat.KEY_HDR_STATIC_INFO)
                                    ?: dolbyVisionFormat?.byteBufferOrNull(MediaFormat.KEY_HDR_STATIC_INFO),
                            ).orElse(readMatroskaHdrStatic(resolver, uri)),
                            dolbyVision = dolbyVision,
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

        /** Null for non-Matroska documents and tracks without HDR10 metadata; best-effort like chapters. */
        private fun readMatroskaHdrStatic(resolver: ContentResolver, uri: Uri): StaticHdrMetadata? =
            runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { stream ->
                        MatroskaHdrStatic.parse(stream.channel)
                    }
                }
            }.getOrNull()

        /** Null for non-Matroska documents and tracks without a Dolby Vision record; best-effort like chapters. */
        private fun readMatroskaDolbyVision(resolver: ContentResolver, uri: Uri): DolbyVisionConfig? =
            runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { stream ->
                        MatroskaDolbyVision.parse(stream.channel)
                    }
                }
            }.getOrNull()

        /** Null for non-Matroska documents and square pixels; best-effort like chapters. */
        private fun readMatroskaSampleAspect(resolver: ContentResolver, uri: Uri): Pair<Int, Int>? =
            runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { stream ->
                        MatroskaVideoAspect.parse(stream.channel)
                    }
                }
            }.getOrNull()

        /** The server's name for a codec MediaExtractor names; null for one it cannot take. */
        private fun codecName(mime: String?): String? = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc"
            MediaFormat.MIMETYPE_VIDEO_AVC -> "h264"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "av1"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "vp9"
            MediaFormat.MIMETYPE_VIDEO_MPEG2 -> "mpeg2video"
            MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "mpeg4"
            else -> null
        }

        /** MediaExtractor's depth for HEVC (from the parameter sets); not a public key. */
        private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"
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
            // The end is the track index going to -1. A negative sample time
            // is an ordinary sample before the start an MP4 edit list trims
            // (an ffmpeg cut starts at -576 ms); reading it as the end ended
            // such a file before its first frame. The wire PTS is signed.
            if (extractor.sampleTrackIndex < 0) return null
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
    // The leading bytes cannot tell the two framings apart: a first NAL of
    // 256..511 bytes has the length 00 00 01 xx, a three-byte start code. Only
    // a sample that parses end to end as length-prefixed units with valid NAL
    // headers is converted; genuine Annex B fails that parse and passes as is.
    val minimumUnit = if (codec == "hevc") 2 else 1
    var offset = 0
    while (offset + 4 <= payload.size) {
        val length = payload.nalLengthAt(offset)
        if (length < minimumUnit || length > payload.size - offset - 4) return payload
        if (!payload.isNalHeaderAt(offset + 4, codec)) return payload
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

/**
 * forbidden_zero_bit is clear in every NAL header, and an HEVC header's
 * nuh_temporal_id_plus1 is never zero.
 */
private fun ByteArray.isNalHeaderAt(offset: Int, codec: String): Boolean {
    if (this[offset].toInt() and 0x80 != 0) return false
    return codec != "hevc" || this[offset + 1].toInt() and 0x07 != 0
}

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
