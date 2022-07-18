package com.lightningkite.lightningserver.websocket

public enum class WebSocketClose(public val code: Short) {
    NORMAL(1000),
    GOING_AWAY(1001),
    PROTOCOL_ERROR(1002),
    CANNOT_ACCEPT(1003),
    CLOSED_ABNORMALLY(1006),
    NOT_CONSISTENT(1007),
    VIOLATED_POLICY(1008),
    TOO_BIG(1009),
    NO_EXTENSION(1010),
    INTERNAL_ERROR(1011),
    SERVICE_RESTART(1012),
    TRY_AGAIN_LATER(1013);
}