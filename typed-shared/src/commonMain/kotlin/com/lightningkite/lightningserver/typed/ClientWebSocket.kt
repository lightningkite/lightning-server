package com.lightningkite.lightningserver.typed

import kotlinx.coroutines.flow.SharedFlow

public interface ClientWebSocket<SEND, RECEIVE> {
    public val connected: SharedFlow<Boolean>

    public fun connect()
    public fun close(code: Short, reason: String)
    public fun send(data: SEND)
    public fun onOpen(action: () -> Unit)
    public fun onMessage(action: (RECEIVE) -> Unit)
    public fun onClose(action: (Short) -> Unit)
}