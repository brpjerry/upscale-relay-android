package org.upscalerelay.demux

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import org.upscalerelay.client.UplinkAccessUnit
import org.upscalerelay.client.UplinkMediaSource
import org.upscalerelay.client.UplinkPacketReader
import org.upscalerelay.client.UplinkVideoInfo
import org.upscalerelay.protocol.ChapterInfo
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** A fresh MediaExtractor and file descriptor are owned by every seek epoch. */
class AndroidMediaSource private constructor(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val videoInfo: UplinkVideoInfo,
    private val videoTrack: Int,
) : UplinkMediaSource {
    private val closed = AtomicBoolean(false)

    override fun openPacketReader(fromPts: Long?): UplinkPacketReader {
        check(!closed.get()) { "local media source is closed" }
        return ExtractorPacketReader(resolver, uri, videoTrack, videoInfo.codec, fromPts)
    }

    override fun close() { closed.set(true) }

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
    resolver: ContentResolver,
    uri: Uri,
    videoTrack: Int,
    private val codec: String,
    fromPts: Long?,
) : UplinkPacketReader {
    private val lock = Any()
    private val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r"))
    private val extractor = MediaExtractor()
    private var closed = false
    private var buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_BYTES)

    init {
        try {
            extractor.setDataSource(descriptor.fileDescriptor)
            extractor.selectTrack(videoTrack)
            if (fromPts != null && fromPts > 0) {
                extractor.seekTo(fromPts, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
        } catch (error: Throwable) {
            extractor.release()
            descriptor.close()
            throw error
        }
    }

    override fun read(): UplinkAccessUnit? = synchronized(lock) {
        if (closed || extractor.sampleTime < 0) return null
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
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        extractor.release()
        descriptor.close()
    }

    companion object {
        private const val DEFAULT_BUFFER_BYTES = 4 * 1024 * 1024
        private const val MAX_ACCESS_UNIT_BYTES = 64 * 1024 * 1024
    }
}

/** Convert common four-byte length-prefixed AVC/HEVC samples to Annex B. */
internal fun normalizeNalUnits(payload: ByteArray, codec: String): ByteArray {
    if (codec !in setOf("h264", "hevc") || payload.size < 4) return payload
    if (payload.startsWithStartCode()) return payload
    val output = ByteArrayOutputStream(payload.size + 16)
    var offset = 0
    while (offset + 4 <= payload.size) {
        val length = ((payload[offset].toInt() and 0xff) shl 24) or
            ((payload[offset + 1].toInt() and 0xff) shl 16) or
            ((payload[offset + 2].toInt() and 0xff) shl 8) or
            (payload[offset + 3].toInt() and 0xff)
        if (length <= 0 || offset + 4 + length > payload.size) return payload
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(payload, offset + 4, length)
        offset += 4 + length
    }
    if (offset != payload.size) return payload
    return output.toByteArray()
}

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
