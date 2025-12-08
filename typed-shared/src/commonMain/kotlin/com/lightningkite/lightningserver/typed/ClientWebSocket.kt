package com.lightningkite.lightningserver.typed

import kotlinx.coroutines.flow.SharedFlow

/**
 * Client-side WebSocket interface with typed message serialization.
 *
 * Provides a reactive API for WebSocket communication with automatic serialization and deserialization.
 * Connection state is tracked via the [connected] flow.
 *
 * @param SEND Type of messages sent to the server
 * @param RECEIVE Type of messages received from the server
 */
public interface ClientWebSocket<SEND, RECEIVE> {
    /**
     * Flow emitting the current connection state.
     * - true when connected
     * - false when disconnected
     *
     * Initial value is typically false before [connect] is called.
     */
    public val connected: SharedFlow<Boolean>

    /**
     * Initiates the WebSocket connection.
     *
     * This is typically called once after setting up message handlers with [onOpen], [onMessage], [onClose].
     * Connection success is indicated by [connected] emitting true and [onOpen] being called.
     */
    public fun connect()

    /**
     * Closes the WebSocket connection with a status code and reason.
     *
     * @param code WebSocket close status code (e.g., 1000 for normal closure)
     * @param reason Human-readable reason for closing
     */
    public fun close(code: Short, reason: String)

    /**
     * Sends a typed message to the server.
     *
     * The message is automatically serialized before transmission. If not connected, behavior is
     * implementation-specific (may throw, queue, or silently fail).
     *
     * @param data Message to send
     */
    public fun send(data: SEND)

    /**
     * Registers a callback to be invoked when the WebSocket connection is successfully established.
     *
     * @param action Callback to execute on connection open
     */
    public fun onOpen(action: () -> Unit)

    /**
     * Registers a callback to be invoked when a message is received from the server.
     *
     * The message is automatically deserialized before being passed to the callback.
     *
     * @param action Callback to execute with each received message
     */
    public fun onMessage(action: (RECEIVE) -> Unit)

    /**
     * Registers a callback to be invoked when the WebSocket connection closes.
     *
     * @param action Callback to execute with the close status code
     */
    public fun onClose(action: (Short) -> Unit)
}

/*
 * TODO: API Improvements
 *
 * 1. Consider adding onError callback for handling connection errors separately from close events
 * 2. Add reconnection support with configurable retry strategy
 * 3. The close() method should accept WebSocket standard close codes (enum) instead of raw Short
 * 4. Consider adding a suspend send() variant that confirms message delivery
 * 5. Add message queue support for sending messages before connection is established
 * 6. Consider providing a Flow<RECEIVE> API in addition to callback-based onMessage
 * 7. Add ping/pong support for connection health monitoring
 */