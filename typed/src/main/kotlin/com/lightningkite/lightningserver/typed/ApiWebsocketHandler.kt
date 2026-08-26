package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.decoder
import com.lightningkite.lightningserver.serialization.encoder
import com.lightningkite.lightningserver.typed.sdk.SDK
import com.lightningkite.lightningserver.typedoutput.emitTypedOutput
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.database.HasId
import kotlinx.serialization.*

public interface ApiWebSocketHandler<PATH : PathSpec, STORAGE, USER : HasId<*>?, INPUT, OUTPUT>
    : WebSocketHandler<PATH, ApiWebSocketStorage<STORAGE>>, SDK.Documentable {
    override val auth: AuthRequirement<USER>
    override val inputType: KSerializer<INPUT>
    override val outputType: KSerializer<OUTPUT>

    public val errorCases: List<LSError>
    public val innerStorageSerializer: KSerializer<STORAGE>

    override val storageSerializer: KSerializer<ApiWebSocketStorage<STORAGE>>
        get() = ApiWebSocketStorage.serializer(innerStorageSerializer)

    /**
     * The typed view of one socket: its request, its decoded state, and typed sending.
     *
     * The socket alone, like [WebSocketConnection] underneath it. The server it runs on arrives
     * separately, as the [ServerRuntime] context of every `*Typed` method below.
     */
    public interface Connection<PATH : PathSpec, STORAGE, USER : HasId<*>?, INPUT, OUTPUT> {
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

    /*
     * The typed phases mirror the raw ones: the server is the context and the socket is an argument,
     * because they have different lifetimes. On a serverless engine each phase is a separate
     * invocation with its own runtime, while the connection persists across all of them.
     */
    public context(serverRuntime: ServerRuntime)
    suspend fun willConnectTyped(access: WebSocketConnectRequestAccess<PATH, USER>): STORAGE

    public context(serverRuntime: ServerRuntime)
    suspend fun didConnectTyped(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>)

    public context(serverRuntime: ServerRuntime)
    suspend fun messageFromClientTyped(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>, frame: INPUT)

    public context(serverRuntime: ServerRuntime)
    suspend fun messageFromSubscriptionTyped(
        connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>,
        topic: WebSocketSubscriptionMessage<*, *>,
    )

    public context(serverRuntime: ServerRuntime)
    suspend fun disconnectTyped(connection: Connection<PATH, STORAGE, USER, INPUT, OUTPUT>, reason: WebSocketClose)


    override context(serverRuntime: ServerRuntime)
    suspend fun willConnect(request: WebSocketConnectRequest<PATH>): ApiWebSocketStorage<STORAGE> {
        return willConnectTyped(WebSocketConnectRequestAccess(request, request.auth(auth))).let {
            ApiWebSocketStorage(
                request.headers.accept.firstOrNull()?.takeUnless { it.type == "*" }
                    ?: request.headers.contentType?.takeUnless { it.type == "*" }
                    ?: request.queryParameters.get("Accept")?.let { MediaType(it) }?.takeUnless { it.type == "*" }
                    ?: request.queryParameters.get("Content-Type")?.let { MediaType(it) }?.takeUnless { it.type == "*" }
                    ?: MediaType.Application.Json, it)
        }
    }

    override context(serverRuntime: ServerRuntime)
    suspend fun didConnect(connection: WebSocketConnection<PATH, ApiWebSocketStorage<STORAGE>>) {
        didConnectTyped(connection.typed())
    }

    override context(serverRuntime: ServerRuntime)
    suspend fun messageFromClient(
        connection: WebSocketConnection<PATH, ApiWebSocketStorage<STORAGE>>,
        frame: WebSocketFrame,
    ) {
        val parsed = try {
            connection.currentState.mediaType.decoder!!(frame, inputType)
        } catch (e: SerializationException) {
            throw BadRequestException(e.message ?: "Could not parse", cause = e)
        }
        messageFromClientTyped(connection.typed(), parsed)
    }

    override context(serverRuntime: ServerRuntime)
    suspend fun messageFromSubscription(
        connection: WebSocketConnection<PATH, ApiWebSocketStorage<STORAGE>>,
        topic: WebSocketSubscriptionMessage<*, *>,
    ) {
        messageFromSubscriptionTyped(connection.typed(), topic)
    }

    override context(serverRuntime: ServerRuntime)
    suspend fun disconnect(
        connection: WebSocketConnection<PATH, ApiWebSocketStorage<STORAGE>>,
        reason: WebSocketClose,
    ) {
        disconnectTyped(connection.typed(), reason)
    }

    /** Presents the raw connection as the typed one this handler's own methods are written against. */
    private context(serverRuntime: ServerRuntime)
    fun WebSocketConnection<PATH, ApiWebSocketStorage<STORAGE>>.typed(): Connection<PATH, STORAGE, USER, INPUT, OUTPUT> =
        ConnectionWrapper<PATH, STORAGE, USER, INPUT, OUTPUT>(serverRuntime, this, outputType, auth)
}


private class ConnectionWrapper<PATH : PathSpec, STORAGE, USER : HasId<*>?, INPUT, OUTPUT>(
    /** Needed for the wrapper's own work — resolving auth and encoding output — not exposed to the socket. */
    private val runtime: ServerRuntime,
    val wraps: WebSocketConnection<PATH, ApiWebSocketStorage<STORAGE>>,
    val outputSerializer: KSerializer<OUTPUT>,
    val authRequirement: AuthRequirement<USER>,
) : ApiWebSocketHandler.Connection<PATH, STORAGE, USER, INPUT, OUTPUT> {
    override suspend fun auth(): Authentication<USER & Any>? = with(runtime) { wraps.request.auth(authRequirement) }
    override val request: WebSocketConnectRequest<PATH> get() = wraps.request
    override val currentState: STORAGE get() = wraps.currentState.storage
    override suspend fun repullState(): STORAGE = wraps.repullState().storage
    override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) =
        wraps.queueStateUpdate { it.copy(storage = modification(it.storage)) }

    override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE =
        wraps.updateStateImmediately { it.copy(storage = modification(it.storage)) }.storage

    override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) = wraps.subscribe(topic)
    override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) = wraps.unsubscribe(topic)
    // The single chokepoint for every typed WebSocket output, model update streams included. See
    // TypedOutputInterceptor for why observation happens before encoding.
    override suspend fun send(frame: OUTPUT): Unit = with(runtime) {
        emitTypedOutput(wraps.request, outputSerializer, frame)
        wraps.send(wraps.currentState.mediaType.encoder!!.ws(wraps.currentState.mediaType, outputSerializer, frame))
    }

    override suspend fun close(reason: WebSocketClose) = wraps.close(reason)
}


@Serializable
public data class ApiWebSocketStorage<STORAGE>(
    val mediaType: MediaType,
    val storage: STORAGE,
    val respondToPings: Boolean = true,
) {
}