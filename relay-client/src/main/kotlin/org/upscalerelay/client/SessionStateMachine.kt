package org.upscalerelay.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState {
    DISCONNECTED,
    CONNECTING,
    BROWSING,
    OPENING,
    BUFFERING,
    SEEKING,
    PLAYING,
    PAUSED,
    CLOSING,
    FAILED,
}

class SessionStateMachine {
    private val mutableState = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    @Synchronized
    fun transition(next: SessionState) {
        val current = mutableState.value
        if (current == next) return
        require(next in allowed.getValue(current)) { "invalid session transition: $current -> $next" }
        mutableState.value = next
    }

    @Synchronized
    fun fail() {
        if (mutableState.value != SessionState.FAILED) mutableState.value = SessionState.FAILED
    }

    companion object {
        private val allowed = mapOf(
            SessionState.DISCONNECTED to setOf(SessionState.CONNECTING),
            SessionState.CONNECTING to setOf(SessionState.BROWSING, SessionState.CLOSING, SessionState.FAILED),
            SessionState.BROWSING to setOf(SessionState.OPENING, SessionState.CLOSING, SessionState.FAILED),
            SessionState.OPENING to setOf(SessionState.BUFFERING, SessionState.CLOSING, SessionState.FAILED),
            SessionState.BUFFERING to setOf(
                SessionState.SEEKING, SessionState.PLAYING, SessionState.PAUSED,
                SessionState.CLOSING, SessionState.FAILED,
            ),
            SessionState.SEEKING to setOf(
                SessionState.BUFFERING, SessionState.SEEKING, SessionState.CLOSING, SessionState.FAILED,
            ),
            SessionState.PLAYING to setOf(
                SessionState.SEEKING, SessionState.PAUSED, SessionState.CLOSING, SessionState.FAILED,
            ),
            SessionState.PAUSED to setOf(
                SessionState.SEEKING, SessionState.PLAYING, SessionState.CLOSING, SessionState.FAILED,
            ),
            SessionState.CLOSING to setOf(SessionState.DISCONNECTED, SessionState.FAILED),
            SessionState.FAILED to setOf(SessionState.CLOSING, SessionState.CONNECTING),
        )
    }
}
