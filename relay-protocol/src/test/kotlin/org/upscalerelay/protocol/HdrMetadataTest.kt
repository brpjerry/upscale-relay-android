package org.upscalerelay.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrMetadataTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a Dolby Vision record gives its profile, level and compatibility id`() {
        // The Cyberpunk Edgerunners profile 8.1 episode's dvvC, as MediaExtractor's csd-2 carries it.
        val record = bytes(0x01, 0x00, 0x10, 0x35, 0x10) + ByteArray(19)
        assertEquals(DolbyVisionConfig(profile = 8, level = 6, blSignalCompatibilityId = 1), DolbyVisionConfig.fromRecord(record))
        // Profile 5 (no compatible base layer), level 9.
        assertEquals(DolbyVisionConfig(5, 9, 0), DolbyVisionConfig.fromRecord(bytes(1, 0, 0x0a, 0x4d, 0x00)))
        // No RPU leaves nothing to carry; a short record nothing to read.
        assertNull(DolbyVisionConfig.fromRecord(bytes(0x01, 0x00, 0x10, 0x31, 0x10)))
        assertNull(DolbyVisionConfig.fromRecord(bytes(0x01, 0x00, 0x10)))
        assertEquals(
            Json.parseToJsonElement("""{"profile":8,"level":6,"bl_signal_compatibility_id":1}"""),
            DolbyVisionConfig(8, 6, 1).toJson(),
        )
    }

    @Test
    fun `static metadata is written in the protocol's shape and only when known`() {
        val mastering = MasteringDisplay(
            primaries = listOf(34000 to 16000, 13250 to 34500, 7500 to 3000),
            whitePoint = 15635 to 16450,
            maxLuminance = 10_000_000,
            minLuminance = 50,
        )
        assertTrue(mastering.valid)
        assertEquals(
            Json.parseToJsonElement(
                """{"mastering_display":{"primaries":[[34000,16000],[13250,34500],[7500,3000]],"white_point":[15635,16450],""" +
                    """"max_luminance":10000000,"min_luminance":50},"content_light_level":{"max_cll":1000,"max_fall":400}}""",
            ),
            StaticHdrMetadata(mastering, ContentLightLevel(1000, 400)).toJson(),
        )
        assertNull(StaticHdrMetadata().toJson())
        // What the server would refuse: min not below max, a coordinate out of range.
        assertFalse(mastering.copy(minLuminance = 10_000_000).valid)
        assertFalse(mastering.copy(whitePoint = 15635 to 60000).valid)
        assertFalse(mastering.copy(primaries = mastering.primaries.take(2)).valid)
    }

    private val startCode = bytes(0, 0, 0, 1)
    private val vps = bytes(0x40, 0x01, 0x0c)
    private val slice = bytes(0x26, 0x01, 0xaf, 0x10)
    private val hdr10PlusPayload = bytes(0xb5, 0x00, 0x3c, 0x00, 0x01, 0x04) + ByteArray(12) { (it + 1).toByte() }

    /** A prefix SEI NAL unit holding one message, without emulation prevention (the tests add it). */
    private fun sei(type: Int, payload: ByteArray) = bytes(0x4e, 0x01, type, payload.size) + payload + bytes(0x80)

    private fun accessUnit(vararg nals: ByteArray) = nals.fold(ByteArray(0)) { unit, nal -> unit + startCode + nal }

    @Test
    fun `an HEVC keyframe with an HDR10+ message is recognised`() {
        assertTrue(hevcAccessUnitHasHdr10Plus(accessUnit(vps, sei(4, hdr10PlusPayload), slice)))
        // Three-byte start codes frame the same units.
        assertTrue(hevcAccessUnitHasHdr10Plus(bytes(0, 0, 1) + sei(4, hdr10PlusPayload) + bytes(0, 0, 1) + slice))
    }

    /** H.265 7.4.2's emulation prevention, as the server's escape_rbsp writes it. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        var zeros = 0
        for (byte in rbsp) {
            if (zeros >= 2 && (byte.toInt() and 0xff) <= 3) {
                out += 3
                zeros = 0
            }
            out += byte
            zeros = if (byte.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    @Test
    fun `an earlier message's escaped zeros do not hide the HDR10+ one`() {
        // Two messages in one SEI unit: four zero bytes of user data, whose
        // escaping (00 00 03 00 00) would throw the message sizes off if it
        // were read as it is, then the HDR10+ message.
        val rbsp = bytes(5, 4, 0, 0, 0, 0) + bytes(4, hdr10PlusPayload.size) + hdr10PlusPayload + bytes(0x80)
        val nal = bytes(0x4e, 0x01) + escape(rbsp)
        assertTrue(nal.size > 2 + rbsp.size)
        assertTrue(hevcAccessUnitHasHdr10Plus(accessUnit(vps, nal, slice)))
    }

    @Test
    fun `other T35 messages, other SEI and no SEI are not HDR10+`() {
        val captions = bytes(0xb5, 0x00, 0x31) + "GA94".toByteArray()
        assertFalse(hevcAccessUnitHasHdr10Plus(accessUnit(vps, sei(4, captions), slice)))
        // A mastering display message (137) with the same bytes is not T.35.
        assertFalse(hevcAccessUnitHasHdr10Plus(accessUnit(vps, sei(137, hdr10PlusPayload), slice)))
        assertFalse(hevcAccessUnitHasHdr10Plus(accessUnit(vps, slice)))
        assertFalse(hevcAccessUnitHasHdr10Plus(ByteArray(0)))
        // A message whose size runs past the unit ends the scan.
        assertFalse(hevcAccessUnitHasHdr10Plus(accessUnit(bytes(0x4e, 0x01, 0x04, 0x40, 0xb5))))
    }

    @Test
    fun `a session is an HDR source when its downlink is HDR or mapped`() {
        val base = SessionInfo(
            sessionId = "s", mediaPort = 1, uplinkToken = null, downlinkToken = "t", downlinkCodec = "hevc",
            downlinkWidth = 1, downlinkHeight = 1, downlinkContainer = "matroska", epoch = 0,
            durationSeconds = null, timeBase = null, averageRate = null, fitMode = "fit", resizeAlgorithm = null,
        )
        assertFalse(base.hdrSource)
        assertFalse(base.copy(downlinkColor = VideoColor(1, 1, 1)).hdrSource)
        assertTrue(base.copy(downlinkColor = VideoColor(9, 16, 9)).hdrSource)
        assertTrue(base.copy(downlinkColor = VideoColor(9, 18, 9)).hdrSource)
        // A Dolby Vision source mapped to SDR is still an HDR source.
        assertTrue(base.copy(downlinkColor = VideoColor(1, 1, 1), downlinkMapping = DownlinkMapping("sdr", null, null, listOf("dolby_vision"))).hdrSource)
    }
}
