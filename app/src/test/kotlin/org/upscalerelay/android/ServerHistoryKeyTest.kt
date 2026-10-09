package org.upscalerelay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerHistoryKeyTest {
    @Test
    fun `a server id names the library wherever the server is reached`() {
        assertEquals("id:relay-1", serverHistoryScope("relay-1", "192.168.0.10", 8590))
        assertEquals(
            serverHistoryScope("relay-1", "192.168.0.10", 8590),
            serverHistoryScope("relay-1", "tower.local", 9000),
        )
    }

    @Test
    fun `without an id the address is the scope, spelled one way`() {
        assertEquals("addr:tower.local:8590", serverHistoryScope(null, " Tower.Local. ", 8590))
        assertEquals("addr:fe80::1:8590", serverHistoryScope(null, "[FE80::1]", 8590))
        assertTrue(serverHistoryScope(null, "h", 1) != serverHistoryScope(null, "h", 2))
    }

    @Test
    fun `the same path on two servers is two entries`() {
        val a = serverHistoryKey("id:alpha", "Shows/ep1.mkv")
        val b = serverHistoryKey("id:bravo", "Shows/ep1.mkv")
        assertTrue(a != b)
        assertEquals("Shows/ep1.mkv", serverHistoryPath(a, "id:alpha"))
        assertNull(serverHistoryPath(a, "id:bravo"))
        assertNull(serverHistoryPath("local:content://x", "id:alpha"))
        // A scope that is a prefix of another does not match it.
        assertNull(serverHistoryPath(serverHistoryKey("id:alpha2", "x.mkv"), "id:alpha"))
    }

    @Test
    fun `history saved before server scoping is dropped`() {
        val stored = encodePositions(
            linkedMapOf(
                "server:Shows/ep1.mkv" to PlaybackProgress(10.0, 100.0),
                serverHistoryKey("id:alpha", "Shows/ep1.mkv") to PlaybackProgress(20.0, 100.0),
                "local:content://doc/a.mkv" to PlaybackProgress(30.0, 100.0),
            ),
        )
        assertEquals(
            setOf(serverHistoryKey("id:alpha", "Shows/ep1.mkv"), "local:content://doc/a.mkv"),
            decodePositions(stored).keys,
        )
        assertEquals(
            listOf(serverHistoryKey("id:alpha", "a.mkv")),
            decodeServerRecents("Shows/old.mkv\n${serverHistoryKey("id:alpha", "a.mkv")}"),
        )
        assertFalse(isServerHistoryKey("Shows/old.mkv"))
    }
}
