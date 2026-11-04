package org.upscalerelay.demux

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.upscalerelay.protocol.ContentLightLevel
import org.upscalerelay.protocol.DolbyVisionConfig
import org.upscalerelay.protocol.MasteringDisplay
import org.upscalerelay.protocol.StaticHdrMetadata
import org.upscalerelay.protocol.VideoColor

/** MediaExtractor's keys as the Tab S9 Ultra reported them for the HDR fixtures (2026-11-03). */
class SourceHdrTest {
    @Test
    fun `the colour keys become H273 code points`() {
        // hdr10-card.mkv: color-standard 6, color-transfer 6, color-range 2.
        assertEquals(VideoColor(9, 16, 9, "limited"), platformColor(standard = 6, transfer = 6, range = 2))
        // hlg.mkv: transfer 7.
        assertEquals(VideoColor(9, 18, 9, "limited"), platformColor(6, 7, 2))
        assertEquals(VideoColor(1, 1, 1, "full"), platformColor(1, 3, 1))
        assertEquals(VideoColor(6, 1, 6, null), platformColor(4, 3, null))
    }

    @Test
    fun `no colour is sent without a transfer it can name`() {
        // sdr10-hevc.mkv reports a range only; sdr10-h264.mkv a hidden
        // standard (71) and no transfer. Sending "unspecified" would make an
        // HDR source SDR on the server, where omitting it lets HEVC decide.
        assertNull(platformColor(standard = null, transfer = null, range = 2))
        assertNull(platformColor(standard = 71, transfer = null, range = 2))
        assertNull(platformColor(standard = 6, transfer = 0, range = 2))
        // A transfer with a standard it cannot name keeps the transfer.
        assertEquals(VideoColor(2, 16, 2, "limited"), platformColor(71, 6, 2))
    }

    @Test
    fun `depth comes from bits-per-sample, else from the profile`() {
        assertEquals(10, sourceBitDepth(bitsPerSample = 10, codec = "hevc", profile = CodecProfileLevel.HEVCProfileMain))
        assertEquals(10, sourceBitDepth(null, "hevc", CodecProfileLevel.HEVCProfileMain10HDR10))
        assertEquals(8, sourceBitDepth(null, "hevc", CodecProfileLevel.HEVCProfileMain))
        // sdr10-h264.mkv: profile 16 (High 10) and no bits-per-sample.
        assertEquals(10, sourceBitDepth(null, "h264", CodecProfileLevel.AVCProfileHigh10))
        assertEquals(8, sourceBitDepth(null, "h264", CodecProfileLevel.AVCProfileHigh))
        assertNull(sourceBitDepth(null, "h264", CodecProfileLevel.AVCProfileHigh422))
        assertEquals(10, sourceBitDepth(null, "vp9", CodecProfileLevel.VP9Profile2HDR))
        assertEquals(10, sourceBitDepth(null, "av1", CodecProfileLevel.AV1ProfileMain10))
        assertNull(sourceBitDepth(null, "hevc", null))
        assertNull(sourceBitDepth(null, "mpeg2video", 1))
        assertEquals(10, sourceBitDepth(7, "hevc", CodecProfileLevel.HEVCProfileMain10))
    }

    private fun cta861(vararg fields: Int): ByteArray = byteArrayOf(0) +
        fields.flatMap { listOf((it and 0xff).toByte(), (it shr 8).toByte()) }.toByteArray()

    @Test
    fun `hdr-static-info gives the mastering display and light levels it knows`() {
        // Primaries R, G, B, white; peak 1000 cd/m2; minimum 0.005 (50 units); MaxCLL 1000, MaxFALL 400.
        val full = cta861(34000, 16000, 13250, 34500, 7500, 3000, 15635, 16450, 1000, 50, 1000, 400)
        assertEquals(
            StaticHdrMetadata(
                MasteringDisplay(listOf(34000 to 16000, 13250 to 34500, 7500 to 3000), 15635 to 16450, 10_000_000, 50),
                ContentLightLevel(1000, 400),
            ),
            staticHdrFromCta861(full),
        )
        // The first container fixture: light levels 10000/400 and zero primaries.
        val lightOnly = byteArrayOf(0) + ByteArray(20) + byteArrayOf(0x10, 0x27, 0x90.toByte(), 0x01)
        assertEquals(StaticHdrMetadata(null, ContentLightLevel(10000, 400)), staticHdrFromCta861(lightOnly))
        assertNull(staticHdrFromCta861(ByteArray(25)))
        assertNull(staticHdrFromCta861(full.copyOf(24)))
        // A descriptor other than type 1.
        assertNull(staticHdrFromCta861(byteArrayOf(1) + full.copyOfRange(1, 25)))
    }

    private fun float32(value: Double) = java.nio.ByteBuffer.allocate(4).putFloat(value.toFloat()).array()
    private fun float64(value: Double) = java.nio.ByteBuffer.allocate(8).putDouble(value).array()

    private fun colourTrack(colour: ByteArray) =
        element(0xAE, element(0x83, uint(1)) + element(0xE0, element(0xB0, uint(1920)) + element(0x55B0, colour)))

    private fun hdrStatic(vararg entries: ByteArray) = MatroskaHdrStatic.parse(
        memoryChannel(mkv(element(0x1654AE6B, entries.fold(ByteArray(0), ByteArray::plus)) + cluster())),
    )

    @Test
    fun `a Matroska Colour element gives the mastering display MediaExtractor drops`() {
        // hdr10-container.mkv's values, as FFmpeg writes them (eight-byte floats).
        val values = listOf(0.68, 0.32, 0.265, 0.69, 0.15, 0.06, 0.3127, 0.329, 1000.0, 0.005)
        val mastering = values.withIndex().fold(ByteArray(0)) { bytes, (index, value) ->
            bytes + element(0x55D1L + index, float64(value))
        }
        val colour = element(0x55BC, uint(1000)) + element(0x55BD, uint(400)) + element(0x55D0, mastering)
        assertEquals(
            StaticHdrMetadata(
                MasteringDisplay(listOf(34000 to 16000, 13250 to 34500, 7500 to 3000), 15635 to 16450, 10_000_000, 50),
                ContentLightLevel(1000, 400),
            ),
            hdrStatic(colourTrack(colour)),
        )
        // Four-byte floats read the same, to rounding.
        val single = values.withIndex().fold(ByteArray(0)) { bytes, (index, value) ->
            bytes + element(0x55D1L + index, float32(value))
        }
        assertEquals(10_000_000L, hdrStatic(colourTrack(element(0x55D0, single)))?.masteringDisplay?.maxLuminance)
        // Light levels alone; a mastering display missing a value is none.
        assertEquals(StaticHdrMetadata(null, ContentLightLevel(1000, 0)), hdrStatic(colourTrack(element(0x55BC, uint(1000)))))
        val partial = values.take(9).withIndex().fold(ByteArray(0)) { bytes, (index, value) ->
            bytes + element(0x55D1L + index, float64(value))
        }
        assertNull(hdrStatic(colourTrack(element(0x55D0, partial))))
        assertNull(hdrStatic(colourTrack(ByteArray(0))))
        assertNull(MatroskaHdrStatic.parse(memoryChannel(ByteArray(64))))
    }

    @Test
    fun `MediaExtractor's halves win and the container fills the rest`() {
        val display = MasteringDisplay(listOf(34000 to 16000, 13250 to 34500, 7500 to 3000), 15635 to 16450, 10_000_000, 50)
        val extractor = StaticHdrMetadata(null, ContentLightLevel(10000, 400))
        val container = StaticHdrMetadata(display, ContentLightLevel(1000, 400))
        assertEquals(StaticHdrMetadata(display, ContentLightLevel(10000, 400)), extractor.orElse(container))
        assertEquals(container, null.orElse(container))
        assertEquals(extractor, extractor.orElse(null))
        assertNull(null.orElse(null))
    }

    @Test
    fun `a Dolby Vision track decodes as its base layer's codec`() {
        assertEquals("hevc", dolbyVisionBaseCodec(8))
        assertEquals("hevc", dolbyVisionBaseCodec(5))
        assertEquals("h264", dolbyVisionBaseCodec(9))
        assertEquals("av1", dolbyVisionBaseCodec(10))
        assertNull(dolbyVisionBaseCodec(20))
        assertEquals("hevc", dolbyVisionBaseCodecOfPlatformProfile(CodecProfileLevel.DolbyVisionProfileDvheSt))
        assertEquals("h264", dolbyVisionBaseCodecOfPlatformProfile(CodecProfileLevel.DolbyVisionProfileDvavSe))
        assertEquals("hevc", dolbyVisionBaseCodecOfPlatformProfile(null))
    }

    @Test
    fun `a Matroska Dolby Vision mapping gives its record`() {
        val record = byteArrayOf(0x01, 0x00, 0x10, 0x35, 0x10) + ByteArray(19)
        fun dvvC(type: Long = 0x64767643, extra: ByteArray = record) =
            element(0x41E4, element(0x41E7, uint(type)) + element(0x41ED, extra))
        fun videoTrack(vararg children: ByteArray) =
            element(0xAE, element(0x83, uint(1)) + children.fold(ByteArray(0), ByteArray::plus))
        fun parse(vararg entries: ByteArray) = MatroskaDolbyVision.parse(
            memoryChannel(mkv(element(0x1654AE6B, entries.fold(ByteArray(0), ByteArray::plus)) + cluster())),
        )
        assertEquals(DolbyVisionConfig(8, 6, 1), parse(videoTrack(dvvC())))
        // dvcC, as profiles up to 7 write it.
        assertEquals(DolbyVisionConfig(8, 6, 1), parse(videoTrack(dvvC(type = 0x64766343))))
        // Another block addition (HDR10+ in Matroska is 4) is not one.
        assertNull(parse(videoTrack(dvvC(type = 4))))
        assertNull(parse(videoTrack()))
        // The first video track is the one read; an audio track before it is skipped.
        val audio = element(0xAE, element(0x83, uint(2)) + dvvC())
        assertEquals(DolbyVisionConfig(8, 6, 1), parse(audio, videoTrack(dvvC())))
        assertNull(parse(videoTrack(), videoTrack(dvvC())))
        assertNull(MatroskaDolbyVision.parse(memoryChannel(ByteArray(64))))
    }
}
