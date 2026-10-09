package org.upscalerelay.demux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class MatroskaVideoAspectTest {
    @Test
    fun `anamorphic PAL sources report their pixel aspect`() {
        // 4:3 and 16:9 DVD shapes stored as 720x576.
        assertEquals(16 to 15, aspectOf(videoTrack(720, 576, 768, 576)))
        assertEquals(64 to 45, aspectOf(videoTrack(720, 576, 1024, 576)))
        // "Display aspect ratio" units give the same shape.
        assertEquals(64 to 45, aspectOf(videoTrack(720, 576, 16, 9, unit = 3)))
    }

    @Test
    fun `square or undeclared display sizes mean square pixels`() {
        assertNull(aspectOf(videoTrack(1920, 1080, 1920, 1080)))
        assertNull(aspectOf(videoTrack(1920, 1080, null, null)))
        assertNull(aspectOf(videoTrack(720, 576, 768, 576, unit = 4)))
        assertNull(aspectOf(videoTrack(720, 576, 0, 576)))
        assertNull(aspectOf(videoTrack(720, 576, 768 * 40, 576)))
    }

    @Test
    fun `the first video track is the one read`() {
        val audio = element(0xAE, element(0x83, uint(2)))
        val tracks = tracks(audio, videoTrack(720, 576, 768, 576), videoTrack(720, 576, 1024, 576))
        assertEquals(16 to 15, MatroskaVideoAspect.parse(memoryChannel(mkv(tracks + cluster()))))
    }

    @Test
    fun `tracks behind the clusters are found through the seek head`() {
        val tracks = tracks(videoTrack(720, 576, 768, 576))
        val clusterBytes = cluster()
        val probe = seekHeadPointingAt(0L)
        val seekHead = seekHeadPointingAt((probe.size + clusterBytes.size).toLong())
        val file = mkv(segmentChildren = seekHead + clusterBytes + tracks)
        assertEquals(16 to 15, MatroskaVideoAspect.parse(memoryChannel(file)))
    }

    @Test
    fun `non matroska and truncated data yield nothing`() {
        assertNull(MatroskaVideoAspect.parse(memoryChannel(ByteArray(64))))
        val file = mkv(tracks(videoTrack(720, 576, 768, 576)))
        assertNull(MatroskaVideoAspect.parse(memoryChannel(file.copyOf(file.size - 3))))
    }

    private fun aspectOf(track: ByteArray): Pair<Int, Int>? =
        MatroskaVideoAspect.parse(memoryChannel(mkv(tracks(track) + cluster())))

    private fun tracks(vararg entries: ByteArray): ByteArray =
        element(0x1654AE6B, entries.fold(ByteArray(0), ByteArray::plus))

    private fun videoTrack(
        pixelWidth: Long,
        pixelHeight: Long,
        displayWidth: Long?,
        displayHeight: Long?,
        unit: Long? = null,
    ): ByteArray {
        var video = element(0xB0, uint(pixelWidth)) + element(0xBA, uint(pixelHeight))
        displayWidth?.let { video += element(0x54B0, uint(it)) }
        displayHeight?.let { video += element(0x54BA, uint(it)) }
        unit?.let { video += element(0x54B2, uint(it)) }
        return element(0xAE, element(0x83, uint(1)) + element(0xE0, video))
    }

    private fun seekHeadPointingAt(tracksPosition: Long): ByteArray {
        val tracksId = byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B)
        val seek = element(0x53AB, tracksId) +
            element(0x53AC, ByteBuffer.allocate(8).putLong(tracksPosition).array())
        return element(0x114D9B74, element(0x4DBB, seek))
    }
}
