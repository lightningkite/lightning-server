package com.lightningkite.lightningserver.typed

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.decoder
import com.lightningkite.lightningserver.serialization.encoder
import com.lightningkite.lightningserver.typed.sdk.SDK
import com.lightningkite.lightningserver.typed.sdk.camelCase
import com.lightningkite.lightningserver.typed.sdk.functionCase
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

public interface ApiWebsocketHandler<PATH : PathSpec, STORAGE, USER : HasId<*>?, INPUT, OUTPUT>
    : WebSocketHandler<PATH, ApiWebsocketStorage<STORAGE>>, SDK.Documentable
{
    override val auth: AuthRequirement<USER>
    override val inputType: KSerializer<INPUT>
    override val outputType: KSerializer<OUTPUT>

    public val errorCases: List<LSError>
    public val innerStorageSerializer: KSerializer<STORAGE>

    override val storageSerializer: KSerializer<ApiWebsocketStorage<STORAGE>>
        get() = ApiWebsocketStorage.serializer(innerStorageSerializer)

    public interface Connection<PATH : PathSpec, STORAGE, USER : HasId<*>?, INPUT, OUTPUT>: ServerRuntime {
        public val request: WebSocketConnectRequest<PATH>
        public val currentState: STORAGE
        public suspend fun auth(): Authentication<USER & Any>?
        public suspend fun repullState(): STORAGE
        public suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE)
        public suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE
        public suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>)
        public suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>)
        public suspend fun send(frame: OUTPUT)
        public suspend fun close(reason: WebSocketClose)
    }

    public context(serverRuntime: ServerRuntime)
    suspend fun willConnectTyped(access: WebSocketConnectRequestAccess<PATH, USER>): STORAGE

    public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
    suspend fun didConnectTyped()

    public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
    suspend fun messageFromClientTyped(frame: INPUT)

    public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
    suspend fun messageFromSubscriptionTyped(topic: WebSocketSubscriptionMessage<*, *>)

    public context(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)
    suspend fun disconnectTyped(reason: WebSocketClose)



    override context(serverRuntime: ServerRuntime)
    suspend fun willConnect(request: WebSocketConnectRequest<PATH>): ApiWebsocketStorage<STORAGE> {
        return willConnectTyped(WebSocketConnectRequestAccess(request, request.auth(auth))).let { ApiWebsocketStorage(
            request.headers.accept.firstOrNull()
                ?: request.headers.contentType
                ?: request.queryParameters.get("Accept")?.let { MediaType(it) }
                ?: request.queryParameters.get("Content-Type")?.let { MediaType(it) }
                ?: MediaType.Application.Json
            , it) }
    }

    override context(connection: WebSocketConnection<PATH, ApiWebsocketStorage<STORAGE>>)
    suspend fun didConnect() {
        with(ConnectionWrapper<PATH, STORAGE, USER, INPUT, OUTPUT>(connection, outputType, auth)) { didConnectTyped() }
    }

    override context(connection: WebSocketConnection<PATH, ApiWebsocketStorage<STORAGE>>)
    suspend fun messageFromClient(frame: WebSocketFrame) {
        val parsed = try {
            connection.currentState.mediaType.decoder!!(frame, inputType)
        } catch(e: SerializationException) {
            throw BadRequestException(e.message ?: "Could not parse", cause = e)
        }
        with(ConnectionWrapper<PATH, STORAGE, USER, INPUT, OUTPUT>(connection, outputType, auth)) { messageFromClientTyped(parsed) }
    }

    override context(connection: WebSocketConnection<PATH, ApiWebsocketStorage<STORAGE>>)
    suspend fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>) {
        with(ConnectionWrapper<PATH, STORAGE, USER, INPUT, OUTPUT>(connection, outputType, auth)) { messageFromSubscriptionTyped(topic) }
    }

    override context(connection: WebSocketConnection<PATH, ApiWebsocketStorage<STORAGE>>)
    suspend fun disconnect(reason: WebSocketClose) {
        with(ConnectionWrapper<PATH, STORAGE, USER, INPUT, OUTPUT>(connection, outputType, auth)) { disconnectTyped(reason) }
    }
}


private class ConnectionWrapper<PATH : PathSpec, STORAGE, USER : HasId<*>?, INPUT, OUTPUT>(
    val wraps: WebSocketConnection<PATH, ApiWebsocketStorage<STORAGE>>,
    val outputSerializer: KSerializer<OUTPUT>,
    val authRequirement: AuthRequirement<USER>
): ApiWebsocketHandler.Connection<PATH, STORAGE, USER, INPUT, OUTPUT>, ServerRuntime by wraps {
    override suspend fun auth(): Authentication<USER & Any>? = wraps.request.auth(authRequirement)
    override val request: WebSocketConnectRequest<PATH> get() = wraps.request
    override val currentState: STORAGE get() = wraps.currentState.storage
    override suspend fun repullState(): STORAGE = wraps.repullState().storage
    override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) = wraps.queueStateUpdate { it.copy(storage = modification(it.storage)) }
    override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE = wraps.updateStateImmediately { it.copy(storage = modification(it.storage)) }.storage
    override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) = wraps.subscribe(topic)
    override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) = wraps.unsubscribe(topic)
    override suspend fun send(frame: OUTPUT) = wraps.send(wraps.currentState.mediaType.encoder!!.ws(wraps.currentState.mediaType, outputSerializer, frame))
    override suspend fun close(reason: WebSocketClose) = wraps.close(reason)
}


@Serializable
public data class ApiWebsocketStorage<STORAGE>(
    val mediaType: MediaType,
    val storage: STORAGE,
    val respondToPings: Boolean = true
) {
}