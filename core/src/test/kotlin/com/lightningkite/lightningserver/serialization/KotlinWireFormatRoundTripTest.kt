@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.websockets.CoroutineWebSocketHandler
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandlerConnectionInfo
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandlerState
import com.lightningkite.lightningserver.websockets.QueryParamWebSocketHandlerData
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.services.data.UuidV7
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// What the server itself writes with its kotlinWireFormat: socket state and task payloads.
class KotlinWireFormatRoundTripTest {
    object TestServer : ServerBuilder()

    private val entry = SerializableCache.Key<Any?, _>("entry", String.serializer())

    @Test
    fun everyExecutionSubclass() = TestServer.test(settings = {}) {
        val wire = engine.internalSerialization.kotlinWireFormat
        val id = Execution.ID(UuidV7.generate())
        val parent = Execution.ID(UuidV7.generate())
        val location = PathSegments.parse("tasks/x")
        val context = SerializableCache<Execution>().apply { set(entry, "carried") }
        val executions = listOf(
            Execution.Http(id, parent, parent, RawHttpEndpoint("a/b", HttpMethod.POST), context),
            Execution.WebSocket(id, parent, parent, socketId = parent, RawWebSocketPath(PathSegments.parse("ws")), Execution.WebSocket.Phase.SubscriptionMessage, context),
            Execution.Task(id, parent, parent, origin = parent, location, context),
            Execution.Schedule(id, location, context),
            Execution.Startup(id, location, context),
            Execution.PreDeploy(id, location, context),
            Execution.Direct(id, parent, parent, context),
        )
        for (execution in executions) {
            val decoded = wire.decodeFromByteArray(Execution.serializer(), wire.encodeToByteArray(Execution.serializer(), execution))
            assertEquals(execution, decoded)
            assertEquals("carried", decoded.context[entry])
        }
    }

    @Test
    fun multiplexAndQueryParamSocketState() = TestServer.test(settings = {}) {
        val wire = engine.internalSerialization.kotlinWireFormat
        val request = WebSocketConnectRequest(
            path = RawWebSocketPath(PathSegments.parse("a/b")),
            queryParameters = QueryParameters(listOf("q" to "1")),
            headers = HttpHeaders { add("X-Test", "x") },
            socketId = Execution.ID(UuidV7.generate()),
        )
        request.cache[entry] = "on the request"

        val multiplex = MultiplexWebSocketHandlerState(
            mapOf(
                "a" to MultiplexWebSocketHandlerConnectionInfo(
                    storage = AnonType(wire, 7, Int.serializer()),
                    topics = setOf("topic/1"),
                    request = request,
                )
            )
        )
        val decodedMultiplex = wire.decodeFromByteArray(
            MultiplexWebSocketHandlerState.serializer(),
            wire.encodeToByteArray(MultiplexWebSocketHandlerState.serializer(), multiplex),
        )
        val channel = decodedMultiplex.map.getValue("a")
        assertEquals(7, channel.storage.value(wire, Int.serializer()))
        assertEquals(setOf("topic/1"), channel.topics)
        assertEquals(request, channel.request)
        assertEquals("on the request", channel.request.cache[entry])

        val queryParam = QueryParamWebSocketHandlerData(request, AnonType(wire, "inner", String.serializer()))
        val decodedQueryParam = wire.decodeFromByteArray(
            QueryParamWebSocketHandlerData.serializer(),
            wire.encodeToByteArray(QueryParamWebSocketHandlerData.serializer(), queryParam),
        )
        assertEquals(request, decodedQueryParam.request)
        assertEquals("inner", decodedQueryParam.underlyingData.value(wire, String.serializer()))
    }

    // Storage's request is declared WebSocketConnectRequest<@Contextual PathSpec0>, which the server's module doesn't register.
    @Test
    fun coroutineSocketStorageAndFrame() = TestServer.test(settings = {}) {
        val wire = engine.internalSerialization.kotlinWireFormat
        val storage = CoroutineWebSocketHandler.Storage(
            id = Uuid.random(),
            request = WebSocketConnectRequest<PathSpec0>(
                path = @OptIn(com.lightningkite.services.data.Unsafe::class) RawWebSocketPath.fromPathSegments(PathSegments.parse("socket")),
                socketId = Execution.ID(UuidV7.generate()),
            ),
        )
        assertEquals(
            storage,
            wire.decodeFromByteArray(CoroutineWebSocketHandler.Storage.serializer(), wire.encodeToByteArray(CoroutineWebSocketHandler.Storage.serializer(), storage)),
        )

        val frame = CoroutineWebSocketHandler.SerializableWebSocketFrame(close = true, failure = "gone", status = HttpStatus.NotFound)
        val decodedFrame = wire.decodeFromByteArray(
            CoroutineWebSocketHandler.SerializableWebSocketFrame.serializer(),
            wire.encodeToByteArray(CoroutineWebSocketHandler.SerializableWebSocketFrame.serializer(), frame),
        )
        assertEquals(frame.close, decodedFrame.close)
        assertEquals(frame.failure, decodedFrame.failure)
        assertEquals(frame.status, decodedFrame.status)
    }
}
