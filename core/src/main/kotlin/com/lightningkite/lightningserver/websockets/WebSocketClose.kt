package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.http.HttpStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

public data class WebSocketClose(
    val code: Code,
    val message: String?,
    val cause: Throwable? = null
) {
    public enum class Code(public val code: Short) {
        NORMAL(1000),
        GOING_AWAY(1001),
        PROTOCOL_ERROR(1002),
        CANNOT_ACCEPT(1003),
        NOT_CONSISTENT(1007),
        VIOLATED_POLICY(1008),
        TOO_BIG(1009),
        NO_EXTENSION(1010),
        INTERNAL_ERROR(1011),
        SERVICE_RESTART(1012),
        TRY_AGAIN_LATER(1013);
    }

    /** Whether this close was caused by a thrown exception, as built by [exceptional]. */
    public val isExceptional: Boolean get() = cause != null

    public companion object {
        public val NORMAL: WebSocketClose get() = WebSocketClose(Code.NORMAL, null, null)
        public val GOING_AWAY: WebSocketClose get() = WebSocketClose(Code.GOING_AWAY, null, null)
        public fun exceptional(exception: Throwable): WebSocketClose =
            WebSocketClose(exception.bestWebSocketCloseCode, exception.message, exception)
    }
}

public val HttpStatus.bestWebSocketCloseCode: WebSocketClose.Code
    get() = when (code / 100) {
        1, 2, 3 -> WebSocketClose.Code.NORMAL
        4 -> WebSocketClose.Code.VIOLATED_POLICY
        else -> WebSocketClose.Code.INTERNAL_ERROR
    }

/**
 * The close code to report for a socket that ended because this exception was thrown.
 *
 * Cancellation is not a failure of the socket: it is the socket's scope tearing it down, which in
 * practice means the server is shutting down. That is [WebSocketClose.GOING_AWAY] — RFC 6455 names
 * "a server going down" as the example for 1001, and clients already read it as "reconnect later".
 * Deriving the code from [HttpStatus.InternalServerError] instead, as every engine used to, reported
 * every routine shutdown as a server fault and buried real 1011s in the noise.
 *
 * (1012 `SERVICE_RESTART` is arguably more precise, but it is only in the IANA registry rather than
 * RFC 6455 proper, so client support for it is thinner. 1001 is the interoperable choice.)
 */
public val Throwable.bestWebSocketCloseCode: WebSocketClose.Code
    get() = when (this) {
        // A handler's own withTimeout expiring is a server fault that happens to arrive as a
        // cancellation. Reporting it as "going away" would bury it exactly the way deriving every
        // cancellation from a 500 used to bury shutdowns — the same mistake, pointed the other way.
        is TimeoutCancellationException -> WebSocketClose.Code.INTERNAL_ERROR
        is CancellationException -> WebSocketClose.Code.GOING_AWAY
        else -> ((this as? HttpStatusException)?.status ?: HttpStatus.InternalServerError).bestWebSocketCloseCode
    }
