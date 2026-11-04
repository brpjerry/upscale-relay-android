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
        capabilities.profileLevels.any { it.profile in MAIN_10_PROFILES } &&
            (video.isSizeSupported(display.width, display.height) ||
                video.isSizeSupported(display.height, display.width))
    }
}
