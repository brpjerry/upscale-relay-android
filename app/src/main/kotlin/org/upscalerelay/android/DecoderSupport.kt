package org.upscalerelay.android

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import org.upscalerelay.protocol.DisplaySize
import java.util.concurrent.ConcurrentHashMap

private val MAIN_10_PROFILES = setOf(
    CodecProfileLevel.HEVCProfileMain10,
    CodecProfileLevel.HEVCProfileMain10HDR10,
    CodecProfileLevel.HEVCProfileMain10HDR10Plus,
)

private val main10Support = ConcurrentHashMap<DisplaySize, Boolean>()

/**
 * Whether a hardware decoder here takes HEVC Main 10 at [display], the size
 * the downlink is made at. Without one, mpv would fall back to software
 * decoding, far below realtime at a tablet's size, so the session asks for
 * nothing and stays 8-bit, as before deep colour.
 */
internal fun decodesHevcMain10(display: DisplaySize): Boolean = main10Support.getOrPut(display) {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        if (info.isEncoder || !info.isHardwareAccelerated) return@any false
        val type = info.supportedTypes.firstOrNull { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            ?: return@any false
        val capabilities = info.getCapabilitiesForType(type)
        val video = capabilities.videoCapabilities ?: return@any false
        // isSizeSupported also wants each side a multiple of the decoder's
        // alignment, and the display need not be one (1848 rows is not a
        // multiple of 16). The stream is coded padded to it and cropped back,
        // so the padded size is the one to ask about.
        fun supports(width: Int, height: Int) = video.isSizeSupported(
            alignUp(width, video.widthAlignment),
            alignUp(height, video.heightAlignment),
        )
        capabilities.profileLevels.any { it.profile in MAIN_10_PROFILES } &&
            (supports(display.width, display.height) || supports(display.height, display.width))
    }
}

/** [value] rounded up to a multiple of [alignment]. */
internal fun alignUp(value: Int, alignment: Int): Int =
    if (alignment <= 1) value else (value + alignment - 1) / alignment * alignment
