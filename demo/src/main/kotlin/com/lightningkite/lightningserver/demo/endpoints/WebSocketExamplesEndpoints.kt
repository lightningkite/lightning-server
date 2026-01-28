package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.websockets.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * WebSocketExamplesEndpoints - Demonstrates real-time communication with WebSockets.
 * 
 * These examples showcase:
 * - Basic WebSocket connections
 * - Pub/Sub pattern with topics
 * - Message broadcasting
 * - Connection lifecycle management
 * - Multiplexed WebSocket connections
 * - Client-to-server and server-to-client messaging
 */
object WebSocketExamplesEndpoints : ServerBuilder() {
    
    /**
     * Topic for chat messages.
     * Topics allow pub/sub pattern - multiple clients can subscribe to receive messages.
     */
    val chatTopic = path.path("ws").path("chat-topic").topic(ChatMessage.serializer())
    
    /**
     * GET /ws/chat/send/{message}
     *
     * HTTP endpoint to send a message to the chat topic.
     * Demonstrates how to send messages to WebSocket clients from HTTP endpoints.
     */
    val sendChatMessage = path.path("ws").path("chat").path("send").arg<String>("message").get bind HttpHandler { request ->
        val message = ChatMessage(
            id = Uuid.random().toString(),
            sender = "HTTP Client",
            content = request.path.arg1,
            timestamp = System.currentTimeMillis()
        )

        chatTopic.send(message)
        HttpResponse.plainText("Message sent to chat!")
    }
    
    /**
     * WebSocket /ws/chat
     *
     * WebSocket endpoint for a simple chat room.
     * Demonstrates:
     * - Connection lifecycle (connect, message, disconnect)
     * - Topic subscription
     * - Broadcasting messages to all connected clients
     */
    val chatSocket = path.path("ws").path("chat") bind WebSocketHandler(
        willConnect = {
            // Called before connection is established
            // Return initial state for this connection
            val sessionId = Uuid.random().toString()
            println("Chat client connecting: $sessionId")
            sessionId
        },
        didConnect = {
            // Called after connection is established
            // Subscribe to the chat topic to receive messages
            println("Chat client connected: $currentState")
            subscribe(chatTopic)

            // Send welcome message
            val welcomeMessage = ChatMessage(
                id = Uuid.random().toString(),
                sender = "System",
                content = "Welcome to the chat! Your session: $currentState",
                timestamp = System.currentTimeMillis()
            )
            send(Json.encodeToString(ChatMessage.serializer(), welcomeMessage))
        },
        messageFromClient = { frame ->
            // Called when a message is received from the client
            // Parse the frame content as ChatMessage
            val clientMessage = Json.decodeFromString(ChatMessage.serializer(), frame.content.toString())
            println("Chat message from $currentState: ${clientMessage.content}")

            val message = ChatMessage(
                id = Uuid.random().toString(),
                sender = currentState,
                content = clientMessage.content,
                timestamp = System.currentTimeMillis()
            )

            // Broadcast to all subscribers of the topic
            chatTopic.send(message)
        },
        topicHandlers = {
            // Handle messages published to topics we're subscribed to
            chatTopic bind { message ->
                println("Broadcasting message to $currentState: ${message.value}")
                send(Json.encodeToString(ChatMessage.serializer(), message.value))
            }
        },
        disconnect = {
            println("Chat client disconnected: $currentState")
        }
    )
    
    /**
     * Topic for live updates (String messages).
     * Demonstrates a simpler topic with primitive types.
     */
    val updatesTopic = path.path("ws").path("updates-topic").topic(String.serializer())
    
    /**
     * GET /ws/updates/broadcast/{message}
     *
     * Broadcast a simple text message to all connected update listeners.
     */
    val broadcastUpdate = path.path("ws").path("updates").path("broadcast").arg<String>("message").get bind HttpHandler { request ->
        updatesTopic.send(request.path.arg1)
        HttpResponse.plainText("Update broadcasted!")
    }
    
    /**
     * WebSocket /ws/updates
     *
     * WebSocket endpoint for receiving live updates.
     * Demonstrates a simpler WebSocket pattern for one-way communication.
     */
    val updatesSocket = path.path("ws").path("updates") bind WebSocketHandler(
        willConnect = { Uuid.random().toString() },
        didConnect = {
            println("Updates client connected: $currentState")
            subscribe(updatesTopic)
            send("Connected to live updates")
        },
        messageFromClient = { frame ->
            println("Client $currentState sent: ${frame.content}")
            send("Echo: ${frame.content}")
        },
        topicHandlers = {
            updatesTopic bind { message ->
                println("Sending update to $currentState: ${message.value}")
                send(message.value)
            }
        },
        disconnect = {
            println("Updates client disconnected: $currentState")
        }
    )
    
    /**
     * WebSocket /ws/multiplex
     *
     * Multiplexed WebSocket connection.
     * Allows multiple logical channels over a single WebSocket connection.
     * Useful for complex applications with many simultaneous real-time data streams.
     */
    val multiplexSocket = path.path("ws").path("multiplex") bind MultiplexWebSocketHandler()
    
    /**
     * WebSocket /ws/echo
     *
     * Simple echo WebSocket - sends back whatever it receives.
     * Demonstrates the most basic WebSocket pattern.
     */
    val echoSocket = path.path("ws").path("echo") bind WebSocketHandler(
        willConnect = { "echo-${Uuid.random()}" },
        didConnect = {
            println("Echo client connected: $currentState")
            send("Echo server ready")
        },
        messageFromClient = { frame ->
            println("Echo from $currentState: ${frame.content}")
            send("Echo: ${frame.content}")
        },
        disconnect = {
            println("Echo client disconnected: $currentState")
        }
    )
}

// Data models for WebSocket messages

@Serializable
data class ChatMessage(
    val id: String,
    val sender: String,
    val content: String,
    val timestamp: Long
)
