@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiWebSocketHandler.Connection
import com.lightningkite.lightningserver.typed.sdk.functionCase
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializerOrContextual
import kotlinx.serialization.KSerializer

@Deprecated("Use corrected spelling, ApiWebSocketHandler", ReplaceWith("ApiWebSocketHandler"))
public inline fun <PATH : PathSpec, reified STORAGE, USER : HasId<*>?, reified INPUT, reified OUTPUT> ApiWebsocketHandler(
    summary: String,
    description: String = "",
    functionName: String = summary.functionCase(),
    auth: AuthRequirement<USER>,
    errorCases: List<LSError> = emptyList(),
//    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    crossinline willConnectType: suspend ServerRuntime.(access: WebSocketConnectRequestAccess<PATH, USER>) -> STORAGE,
    crossinline didConnectType: suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.() -> Unit = {},
    crossinline messageFromClientType: suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(frame: INPUT) -> Unit = {},
    crossinline topicHandlersType: ApiTopicHandlersBuilder<PATH, STORAGE, USER, INPUT, OUTPUT>.() -> Unit = {},
    crossinline disconnectType: suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(reason: WebSocketClose) -> Unit = {},
): ApiWebSocketHandler<PATH, STORAGE, USER, INPUT, OUTPUT> = ApiWebSocketHandler(
    summary = summary,
    description = description,
    functionName = functionName,
    auth = auth,
    errorCases = errorCases,
    willConnectType = willConnectType,
    didConnectType = didConnectType,
    messageFromClientType = messageFromClientType,
    topicHandlersType = topicHandlersType,
    disconnectType = disconnectType,
)

/**
 * Builds a typed handler from one lambda per lifecycle phase.
 *
 * As with the raw `WebSocketHandler` builder, the connection is the lambdas' receiver and the
 * [ServerRuntime] their context, which is the opposite of how [ApiWebSocketHandler] declares them. A
 * socket body spends most of its lines on the socket — `send`, `currentState`, `subscribe` — so that
 * is what `this` should be, while the runtime is what the settings and service accessors want and
 * they take it as a context anyway.
 */
public inline fun <PATH : PathSpec, reified STORAGE, USER : HasId<*>?, reified INPUT, reified OUTPUT> ApiWebSocketHandler(
    summary: String,
    description: String = "",
    functionName: String = summary.functionCase(),
    auth: AuthRequirement<USER>,
    errorCases: List<LSError> = emptyList(),
//    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    crossinline willConnectType: suspend ServerRuntime.(access: WebSocketConnectRequestAccess<PATH, USER>) -> STORAGE,
    crossinline didConnectType: suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.() -> Unit = {},
    crossinline messageFromClientType: suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(frame: INPUT) -> Unit = {},
    crossinline topicHandlersType: ApiTopicHandlersBuilder<PATH, STORAGE, USER, INPUT, OUTPUT>.() -> Unit = {},
    crossinline disconnectType: suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(reason: WebSocketClose) -> Unit = {},
): ApiWebSocketHandler<PATH, STORAGE, USER, INPUT, OUTPUT> =
    object : ApiWebSocketHandler<PATH, STORAGE, USER, INPUT, OUTPUT> {
        override val summary: String = summary
        override val description: String = description
        override val functionName: String = functionName
        override val inputType: KSerializer<INPUT> = serializerOrContextual()
        override val outputType: KSerializer<OUTPUT> = serializerOrContextual()
        override val auth: AuthRequirement<USER> = auth
        override val errorCases: List<LSError> = errorCases

        //        override val examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = examples
        override val innerStorageSerializer: KSerializer<STORAGE> = serializerOrContextual()

        public context(serverRuntime: ServerRuntime)
        override suspend fun willConnectTyped(access: WebSocketConnectRequestAccess<PATH, USER>): STORAGE =
            willConnectType(serverRuntime, access)

        public context(serverRuntime: ServerRuntime)
        override suspend fun didConnectTyped(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>): Unit =
            didConnectType(serverRuntime, connection)

        public context(serverRuntime: ServerRuntime)
        override suspend fun messageFromClientTyped(
            connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>,
            frame: INPUT,
        ): Unit = messageFromClientType(serverRuntime, connection, frame)

        private val subHandler =
            ApiTopicHandlersBuilder<PATH, STORAGE, USER, INPUT, OUTPUT>().apply(topicHandlersType).build()

        public context(serverRuntime: ServerRuntime)
        override suspend fun messageFromSubscriptionTyped(
            connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>,
            topic: WebSocketSubscriptionMessage<*, *>,
        ): Unit = subHandler(serverRuntime, connection, topic)

        public context(serverRuntime: ServerRuntime)
        override suspend fun disconnectTyped(
            connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>,
            reason: WebSocketClose,
        ): Unit = disconnectType(serverRuntime, connection, reason)
    }


public class ApiTopicHandlersBuilder<PATH : PathSpec, STORAGE, USER : HasId<*>?, INPUT, OUTPUT>() {
    public var handler: suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit =
        {}

    @Suppress("UNCHECKED_CAST", "DSL_MARKER_APPLIED_TO_WRONG_TARGET")
    @LightningServerDsl
    public inline infix fun <TOPICPATH : PathSpec, T> WebSocketTopic<TOPICPATH, T>.bind(
        crossinline handler: suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(topic: WebSocketSubscriptionMessage<TOPICPATH, T>) -> Unit,
    ) {
        val topic = this
        this@ApiTopicHandlersBuilder.handler = this@ApiTopicHandlersBuilder.handler.let { current ->
            { it: WebSocketSubscriptionMessage<*, *> ->
                if (topic == it.topic) handler(it as WebSocketSubscriptionMessage<TOPICPATH, T>)
                else current(it)
            }
        }
    }

    public fun build(): suspend context(ServerRuntime) Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit =
        handler
}
