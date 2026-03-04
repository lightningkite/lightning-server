@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler.*
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.database.*
import kotlinx.serialization.*

@Suppress("RedundantSuspendModifier", "UnusedReceiverParameter")
@InternalLightningServerApi
public suspend fun <PATH: PathSpec, STORAGE, USER: HasId<*>?, INPUT, OUTPUT> Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.didConnectNoOp(): Unit = Unit
@Suppress("RedundantSuspendModifier", "UnusedReceiverParameter")
@InternalLightningServerApi
public suspend fun <PATH: PathSpec, STORAGE, USER: HasId<*>?, INPUT, OUTPUT> Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.messageFromClientNoOp(frame: INPUT): Unit = Unit
@Suppress("RedundantSuspendModifier", "UnusedReceiverParameter")
@InternalLightningServerApi
public suspend fun <PATH: PathSpec, STORAGE, USER: HasId<*>?, INPUT, OUTPUT> Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.disconnectNoOp(reason: WebSocketClose): Unit = Unit

//public fun <PATH: PathSpec, STORAGE, USER: HasId<*>?, INPUT, OUTPUT> ApiWebsocketHandler(
//    summary: String,
//    description: String = "",
//    functionName: String = summary.functionCase(),
//    storageSerializer: KSerializer<STORAGE>,
//    inputType: KSerializer<INPUT>,
//    outputType: KSerializer<OUTPUT>,
//    auth: AuthRequirement<USER>,
//    errorCases: List<LSError> = emptyList(),
////    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
//    willConnectType: suspend ServerRuntime.(access: WebSocketConnectRequestAccess<PATH, USER>) -> STORAGE,
//    didConnectType: suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.() -> Unit = Connection<PATH, STORAGE, USER, INPUT, OUTPUT>::didConnectNoOp,
//    messageFromClientType: suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(frame: INPUT) -> Unit = Connection<PATH, STORAGE, USER, INPUT, OUTPUT>::messageFromClientNoOp,
//    topicHandlersType: ApiTopicHandlersBuilder<PATH, STORAGE, USER, INPUT, OUTPUT>.()->Unit = {},
//    disconnectType: suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(reason: WebSocketClose) -> Unit = Connection<PATH, STORAGE, USER, INPUT, OUTPUT>::disconnectNoOp,
//): ApiWebsocketHandler<PATH, STORAGE, USER, INPUT, OUTPUT> =
//    object : ApiWebsocketHandler<PATH, STORAGE, USER, INPUT, OUTPUT> {
//        override val summary: String = summary
//        override val description: String = description
//        override val functionName: String = functionName
//        override val inputType: KSerializer<INPUT> = inputType
//        override val outputType: KSerializer<OUTPUT> = outputType
//        override val auth: AuthRequirement<USER> = auth
//        override val errorCases: List<LSError> = errorCases
////        override val examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = examples
//        override val innerStorageSerializer: KSerializer<STORAGE> = storageSerializer
//
//        public context(serverRuntime: ServerRuntime)
//        override suspend fun willConnectTyped(access: WebSocketConnectRequestAccess<PATH, USER>): STORAGE = willConnectType(serverRuntime, access)
//
//        public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
//        override suspend fun didConnectTyped(): Unit = didConnectType(connection, )
//
//        public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
//        override suspend fun messageFromClientTyped(frame: INPUT): Unit = messageFromClientType(connection, frame)
//
//        private val subHandler = ApiTopicHandlersBuilder<PATH, STORAGE, USER, INPUT, OUTPUT>().apply(topicHandlersType).build()
//        public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
//        override suspend fun messageFromSubscriptionTyped(topic: WebSocketSubscriptionMessage<*, *>): Unit = subHandler(contextOf(), topic)
//
//        public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
//        override suspend fun disconnectTyped(reason: WebSocketClose): Unit = disconnectType(connection, reason)
//    }

public inline fun <PATH: PathSpec, reified STORAGE, USER: HasId<*>?, reified INPUT, reified OUTPUT> ApiWebsocketHandler(
    summary: String,
    description: String = "",
    functionName: String = summary.functionCase(),
    auth: AuthRequirement<USER>,
    errorCases: List<LSError> = emptyList(),
//    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    crossinline willConnectType: suspend ServerRuntime.(access: WebSocketConnectRequestAccess<PATH, USER>) -> STORAGE,
    crossinline didConnectType: suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.() -> Unit = Connection<PATH, STORAGE, USER, INPUT, OUTPUT>::didConnectNoOp,
    crossinline messageFromClientType: suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(frame: INPUT) -> Unit = Connection<PATH, STORAGE, USER, INPUT, OUTPUT>::messageFromClientNoOp,
    crossinline topicHandlersType: ApiTopicHandlersBuilder<PATH, STORAGE, USER, INPUT, OUTPUT>.()->Unit = {},
    crossinline disconnectType: suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(reason: WebSocketClose) -> Unit = Connection<PATH, STORAGE, USER, INPUT, OUTPUT>::disconnectNoOp,
): ApiWebsocketHandler<PATH, STORAGE, USER, INPUT, OUTPUT> =
    object : ApiWebsocketHandler<PATH, STORAGE, USER, INPUT, OUTPUT> {
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
        override suspend fun willConnectTyped(access: WebSocketConnectRequestAccess<PATH, USER>): STORAGE = willConnectType(serverRuntime, access)

        public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
        override suspend fun didConnectTyped(): Unit = didConnectType(connection, )

        public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
        override suspend fun messageFromClientTyped(frame: INPUT): Unit = messageFromClientType(connection, frame)

        private val subHandler = ApiTopicHandlersBuilder<PATH, STORAGE, USER, INPUT, OUTPUT>().apply(topicHandlersType).build()
        public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
        override suspend fun messageFromSubscriptionTyped(topic: WebSocketSubscriptionMessage<*, *>): Unit = subHandler(contextOf<Connection<PATH, STORAGE, USER, INPUT, OUTPUT>>(), topic)

        public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
        override suspend fun disconnectTyped(reason: WebSocketClose): Unit = disconnectType(connection, reason)
    }


public class ApiTopicHandlersBuilder<PATH: PathSpec, STORAGE, USER: HasId<*>?, INPUT, OUTPUT>() {
    public var handler: suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit = {}

    @LightningServerDsl
    @Suppress("UNCHECKED_CAST")
    public inline infix fun <TOPICPATH: PathSpec, T> WebSocketTopic<TOPICPATH, T>.bind(
        crossinline handler: suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(topic: WebSocketSubscriptionMessage<TOPICPATH, T>) -> Unit
    ) {
        val topic = this
        this@ApiTopicHandlersBuilder.handler = this@ApiTopicHandlersBuilder.handler.let { current ->
            { it: WebSocketSubscriptionMessage<*, *> ->
                if (topic == it.topic) handler(it as WebSocketSubscriptionMessage<TOPICPATH, T>)
                else current(it)
            }
        }
    }

    public fun build(): suspend Connection<PATH, STORAGE, USER, INPUT, OUTPUT>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit = handler
}
