package com.lightningkite.lightningserver.typed

import com.lightningkite.UUID
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websocket.TypeRetriever
import com.lightningkite.lightningserver.websocket.WebSocketClose
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.lightningserver.websocket.WebSocketTopic
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.lightningserver.websocket.test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.serializer
import kotlin.test.Test

class ApiWebsocketTest {
    @Serializable data class TestType(
        val x: Int = 42,
        val y: String = "Test"
    )

    val route = ServerPath("test-api-websocket").typed
    val s = object: ApiWebsocket<HasId<*>?, TypedServerPath0, TestType, TestType, TestType>() {
        override val path: TypedServerPath0 = route
        override val authOptions: AuthOptions<HasId<*>?> = noAuth
        override val inputType: KSerializer<TestType> = TestType.serializer()
        override val outputType: KSerializer<TestType> = TestType.serializer()
        override val storageSerializer: KSerializer<TestType> = TestType.serializer()
        override val summary: String = "Test"

        val general = WebSocketTopic("sample", TestType.serializer())

        override suspend fun AuthAndPathParts<HasId<*>?, TypedServerPath0>.willConnect(
            request: WebSocketConnectRequest
        ): TestType  {
            val id = UUID.random().toString()
            println("$id willConnect")
            return TestType(y = id)
        }

        override suspend fun didConnect(
            connection: Mid<HasId<*>?, TypedServerPath0, TestType, TestType, TestType>,
            request: WebSocketConnectRequest
        ) {
            println("${connection.currentState} didConnect")
            connection.subscribe(general)
        }

        override suspend fun messageFromClient(
            connection: Mid<HasId<*>?, TypedServerPath0, TestType, TestType, TestType>,
            input: TestType
        ) {
            println("${connection.currentState} messageFromClient $input")
            general.publish(input)
        }

        override suspend fun messageFromSubscription(
            connection: Mid<HasId<*>?, TypedServerPath0, TestType, TestType, TestType>,
            topic: String,
            retriever: TypeRetriever
        ) {
            println("${connection.currentState} messageFromSubscription ${retriever(general.type)}")
            connection.send(retriever(general.type))
        }

        override suspend fun disconnect(
            connection: Mid<HasId<*>?, TypedServerPath0, TestType, TestType, TestType>,
            reason: WebSocketClose
        ) {
            println("${connection.currentState} disconnect")
        }

        init {
            WebSockets.handlers[path.path] = this.raw
        }
    }

    @Test fun test(): Unit = runBlocking {
        s.test(AuthAndPathParts.test(null)) {
            send(TestType(y = "My Test"))
            println(incoming.receive())
            println("OK")
        }
    }

    @Test fun mapsToRealSocket(): Unit = runBlocking {
        s.path.path.test(
            parts = mapOf(),
        ) {
            send("{\"y\": \"Test\"}")
            println(incoming.receive())
            println("OK")
        }
    }

    @Test fun mapsToRealSocketCsv(): Unit = runBlocking {
        s.path.path.test(
            parts = mapOf(),
            headers = HttpHeaders { set(HttpHeader.ContentType, ContentType.Text.CSV.toString()) },
        ) {
            send("x,y\n42,Test")
            println(incoming.receive())
            println("OK")
        }
    }

    @Test fun mapsToRealSocketProtobuf(): Unit = runBlocking {
        Serialization.enablePublicProtobuf()
        s.path.path.test(
            parts = mapOf(),
            headers = HttpHeaders { set(HttpHeader.ContentType, ContentType.Application.ProtoBuf.toString()) },
        ) {
            send(Serialization.protobuf.encodeToByteArray(TestType(x = 10, y = "My Test")))
            println(incoming.receive())
            println("OK")
        }
    }
}