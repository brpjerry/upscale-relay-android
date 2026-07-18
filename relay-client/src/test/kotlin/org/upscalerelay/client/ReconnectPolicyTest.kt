package org.upscalerelay.client

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    private val policy = ReconnectPolicy(
        maxAttempts = 6,
        initialDelayMillis = 1_000,
        maxDelayMillis = 8_000,
        budgetMillis = 60_000,
    )

    @Test
    fun `delays back off exponentially and cap`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L),
            (1..6).map(policy::delayBeforeAttempt))
    }

    @Test
    fun `a ten second interruption fits well inside the budget`() {
        // Cumulative wait through attempt 4 exceeds 10 s, and both the attempt
        // count and the elapsed budget still allow that attempt to run.
        val cumulative = (1..4).sumOf(policy::delayBeforeAttempt)
        assertTrue(cumulative >= 10_000)
        assertTrue(policy.shouldAttempt(4, cumulative, FailureKind.NETWORK_LOST))
    }

    @Test
    fun `attempts stop at the attempt cap and the time budget`() {
        assertTrue(policy.shouldAttempt(6, 30_000, FailureKind.NETWORK_LOST))
        assertFalse(policy.shouldAttempt(7, 30_000, FailureKind.NETWORK_LOST))
        assertFalse(policy.shouldAttempt(2, 60_000, FailureKind.NETWORK_LOST))
    }

    @Test
    fun `unrecoverable kinds never retry`() {
        assertFalse(policy.shouldAttempt(1, 0, FailureKind.SERVER_REJECTED))
        assertFalse(policy.shouldAttempt(1, 0, FailureKind.UNSUPPORTED))
    }

    @Test
    fun `classification maps transport failures to recoverable kinds`() {
        assertEquals(FailureKind.NETWORK_LOST, classifyFailure(SocketException("Software caused connection abort")))
        assertEquals(FailureKind.CONNECT_TIMEOUT, classifyFailure(SocketTimeoutException("connect timed out")))
        assertEquals(FailureKind.CONNECT_TIMEOUT, classifyFailure(ConnectException("Connection refused")))
        assertEquals(FailureKind.SERVER_CLOSED, classifyFailure(EOFException("downlink ended")))
        assertEquals(FailureKind.SERVER_CLOSED, classifyFailure(IOException("control channel closed: 1000 bye")))
        assertEquals(FailureKind.NETWORK_LOST, classifyFailure(IOException("Connection reset")))
        assertEquals(FailureKind.MEDIA_STALLED, classifyFailure(MediaStalledException("no media for 15 s")))
        assertTrue(classifyFailure(IOException("anything else")).recoverable)
    }

    @Test
    fun `classification maps protocol and capability problems to unrecoverable kinds`() {
        assertEquals(FailureKind.SERVER_REJECTED, classifyFailure(RelayServerException("bad_request", "nope")))
        assertEquals(FailureKind.UNSUPPORTED, classifyFailure(IllegalStateException("server does not support tier")))
        assertEquals(FailureKind.UNSUPPORTED, classifyFailure(IllegalArgumentException("unknown fit mode")))
        assertFalse(FailureKind.SERVER_REJECTED.recoverable)
    }
}
