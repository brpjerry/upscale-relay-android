package org.upscalerelay.client

import kotlinx.coroutines.TimeoutCancellationException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Classified failure causes so the recovery UI can say what happened and the
 * reconnect loop can tell transient network conditions apart from problems a
 * retry cannot fix (protocol or capability mismatches).
 */
enum class FailureKind(val recoverable: Boolean, val label: String) {
    NETWORK_LOST(true, "Network connection lost"),
    CONNECT_TIMEOUT(true, "Server did not respond"),
    SERVER_CLOSED(true, "Server closed the connection"),
    MEDIA_STALLED(true, "Media stream stalled"),
    SERVER_REJECTED(false, "Server rejected the request"),
    UNSUPPORTED(false, "Unsupported configuration"),
    UNKNOWN(true, "Playback failed"),
}

/** Marker for the client-side stall watchdog (no packets while playing). */
class MediaStalledException(message: String) : IOException(message)

fun classifyFailure(error: Throwable): FailureKind = when (error) {
    is MediaStalledException -> FailureKind.MEDIA_STALLED
    is RelayServerException -> FailureKind.SERVER_REJECTED
    is SocketTimeoutException, is TimeoutCancellationException -> FailureKind.CONNECT_TIMEOUT
    is ConnectException, is NoRouteToHostException, is UnknownHostException -> FailureKind.CONNECT_TIMEOUT
    is SocketException -> FailureKind.NETWORK_LOST
    is EOFException -> FailureKind.SERVER_CLOSED
    is IOException -> {
        val text = error.message.orEmpty().lowercase()
        when {
            "closed" in text -> FailureKind.SERVER_CLOSED
            "reset" in text || "abort" in text || "unreachable" in text || "broken" in text ->
                FailureKind.NETWORK_LOST
            else -> FailureKind.NETWORK_LOST
        }
    }
    is IllegalArgumentException, is IllegalStateException -> FailureKind.UNSUPPORTED
    else -> FailureKind.UNKNOWN
}
