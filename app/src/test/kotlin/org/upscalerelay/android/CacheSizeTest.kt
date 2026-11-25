package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.upscalerelay.protocol.Capabilities
import org.upscalerelay.protocol.QualityOption

class CacheSizeTest {
    private fun caps(vararg options: QualityOption) = Capabilities(
        protocolVersion = 1, serverName = "s", models = emptyList(),
        qualityTiers = options.map { it.id }, qualityOptions = options.toList(),
        hasLibrary = true, resizeAlgorithms = emptyList(), defaultResizeAlgorithm = "lanczos",
    )
    private val qp10 = QualityOption("hevc-qp10", "HEVC QP10", "hevc", false, true, 40)
    private val lossless = QualityOption("lossless-hevc", "Lossless", "hevc", true, true, null)

    // Bugs caught: 0, False positives: 0
    @Test
    fun `sizes read as the desktop shows them and only offered ones are kept`() {
        assertEquals("256 MB", formatCacheSize(256))
        assertEquals("1 GB", formatCacheSize(1024))
        assertEquals("1.5 GB", formatCacheSize(1536))
        assertEquals("5 GB", formatCacheSize(5120))
        assertEquals(1536, validCacheMib(1536))
        assertEquals(CACHE_DEFAULT_MIB, validCacheMib(1000))
        assertEquals(768, CACHE_DEFAULT_MIB)
    }

    // Bugs caught: 0, False positives: 0
    @Test
    fun `a session opens with the cache's length at the quality's bitrate`() {
        // 768 MiB at 40 Mbps.
        assertEquals(161_061L, cacheTargetEstimateMillis(768, caps(qp10, lossless), "hevc-qp10"))
        // Lossless has no set bitrate: about where 4K runs.
        assertEquals(16_106L, cacheTargetEstimateMillis(768, caps(qp10, lossless), "lossless-hevc"))
        assertEquals(16_106L, cacheTargetEstimateMillis(768, null, "hevc-qp10"))
    }

    // Bugs caught: 0, False positives: 0
    @Test
    fun `playing, the target follows what the cache holds`() {
        // 20 s in 100 MiB: 768 MiB lasts 153.6 s.
        assertEquals(153_600L, cacheTargetMillis(768, 100 * MIB, 20_000))
        assertNull(cacheTargetMillis(768, 1 * MIB, 500))
    }

    // Bugs caught: 0, False positives: 0
    @Test
    fun `the hint says how long the cache holds, or that the server will not fill it`() {
        assertEquals(
            "Holds about 3 min at 40 Mbps. Actual length varies with the video.",
            cacheSizeHint(768, caps(qp10), "hevc-qp10", serverKeepsFixedBuffer = false),
        )
        assertEquals(
            "Holds about 16 s at 400 Mbps. Actual length varies with the video.",
            cacheSizeHint(768, caps(lossless), "lossless-hevc", serverKeepsFixedBuffer = false),
        )
        assertTrue(cacheSizeHint(768, caps(qp10), "hevc-qp10", serverKeepsFixedBuffer = true).contains("only 10 s"))
        assertEquals("", cacheSizeHint(768, null, "hevc-qp10", serverKeepsFixedBuffer = false))
    }
}
