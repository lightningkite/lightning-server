package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.http.HttpStatus

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