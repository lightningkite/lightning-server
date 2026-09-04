package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.http.HttpStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

public enum class WebSocketClose(public val code: Short) {
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

public val HttpStatus.bestWebSocketCloseCode: WebSocketClose
    get() = when (code / 100) {
        1, 2, 3 -> WebSocketClose.NORMAL
        4 -> WebSocketClose.VIOLATED_POLICY
        else -> WebSocketClose.INTERNAL_ERROR
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
public val Throwable.webSocketCloseReason: WebSocketClose
    get() = when {
        // A handler's own withTimeout expiring is a server fault that happens to arrive as a
        // cancellation. Reporting it as "going away" would bury it exactly the way deriving every
        // cancellation from a 500 used to bury shutdowns — the same mistake, pointed the other way.
        this is TimeoutCancellationException -> WebSocketClose.INTERNAL_ERROR
        this is CancellationException -> WebSocketClose.GOING_AWAY
        else -> ((this as? HttpStatusException)?.status ?: HttpStatus.InternalServerError).bestWebSocketCloseCode
    }
