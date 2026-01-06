package com.lightningkite.lightningserver.websockets

/**
 * Interface for engines that support direct WebSocket message sending.
 *
 * This allows bypassing the pub/sub mechanism for sending messages to specific sockets
 * when the socket ID is known. Primary use case is [CoroutineWebsocketHandler] where
 * the background task knows the exact socket to send to.
 *
 * Implementations should handle connection cleanup when sockets are gone.
 */
public interface DirectWebSocketSender {
    /**
     * Sends a frame directly to a specific WebSocket connection.
     *
     * @param socketId The engine-specific socket identifier (e.g., AWS connection ID)
     * @param frame The frame to send
     * @return true if the message was sent successfully, false if the connection is gone
     */
    public suspend fun sendDirect(socketId: String, frame: WebSocketFrame): Boolean
}
