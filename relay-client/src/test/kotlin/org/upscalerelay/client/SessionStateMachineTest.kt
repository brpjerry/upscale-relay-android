package org.upscalerelay.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun `happy path and idempotent close transitions`() {
        val states = SessionStateMachine()
        states.transition(SessionState.CONNECTING)
        states.transition(SessionState.BROWSING)
        states.transition(SessionState.OPENING)
        states.transition(SessionState.BUFFERING)
        states.transition(SessionState.PLAYING)
        states.transition(SessionState.PAUSED)
        states.transition(SessionState.PLAYING)
        states.transition(SessionState.SEEKING)
        states.transition(SessionState.BUFFERING)
        states.transition(SessionState.PLAYING)
        states.transition(SessionState.CLOSING)
        states.transition(SessionState.CLOSING)
        states.transition(SessionState.DISCONNECTED)
        assertEquals(SessionState.DISCONNECTED, states.state.value)
    }

    @Test
    fun `invalid transition is rejected`() {
        val states = SessionStateMachine()
        assertThrows(IllegalArgumentException::class.java) {
            states.transition(SessionState.PLAYING)
        }
    }

    @Test
    fun `failure can be retried`() {
        val states = SessionStateMachine()
        states.transition(SessionState.CONNECTING)
        states.fail()
        states.transition(SessionState.CONNECTING)
        assertEquals(SessionState.CONNECTING, states.state.value)
    }
}
