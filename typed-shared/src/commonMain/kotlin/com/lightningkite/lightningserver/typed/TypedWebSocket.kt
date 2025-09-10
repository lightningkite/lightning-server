package com.lightningkite.lightningserver.typed

import kotlinx.coroutines.flow.StateFlow

public interface TypedWebSocket<SEND, RECEIVE> {
    public val connected: StateFlow<Boolean>

    public fun close(code: Short, reason: String)
    public fun send(data: SEND)
    public fun onOpen(action: () -> Unit)
    public fun onMessage(action: (RECEIVE) -> Unit)
    public fun onClose(action: (Short) -> Unit)
}