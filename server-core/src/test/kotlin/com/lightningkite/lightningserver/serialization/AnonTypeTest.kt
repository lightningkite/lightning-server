package com.lightningkite.lightningserver.serialization

import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.Mask
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.RequestAuthSerializable
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelRestUpdatesWebsocketData
import com.lightningkite.lightningserver.engine.LocalEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.prepareModelsServerCoreTest
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.pubsub.PubSub
import com.lightningkite.lightningserver.websocket.MultiplexWebSocketHandlerConnectionInfo
import com.lightningkite.lightningserver.websocket.MultiplexWebSocketHandlerState
import com.lightningkite.lightningserver.websocket.QueryParamWebSocketHandlerData
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.now
import kotlinx.serialization.Contextual
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

class AnonTypeTest {
    @Serializable data class Complex(val x: Int = 0, val y: String = "", val z: Complex? = null)
    @Test fun all() {
        for(encoding in InternalCommunicationEncoding.values()) {
            println("$encoding")
            engine = LocalEngine(LocalPubSub, encoding)
            val values = listOf(
                AnonType(5, Int.serializer()),
                AnonType("Text", String.serializer()),
                AnonType(Complex(42, "OK", Complex(1, "done")), Complex.serializer()),
            )
            for(value in values) {
                val innerSerializer = value.serializer!!
                println(" ${value.value(innerSerializer)}")
                assertEquals(
                    value.value(innerSerializer),
                    Serialization.json.encodeToString(value).also {
                        println("   json wrapped: ${it}")
                        println("   json direct:  ${Serialization.json.encodeToString(value.serializer as KSerializer<Any?>, value.direct!!)}")
                    }.let { Serialization.json.decodeFromString<AnonType>(it) }.value(innerSerializer)
                )
                assertEquals(
                    value.value(innerSerializer),
                    Serialization.cbor.encodeToBase64(value).also {
                        println("   cbor wrapped: ${it}")
                        println("   cbor direct:  ${Serialization.cbor.encodeToBase64(value.serializer as KSerializer<Any?>, value.direct!!)}")
                    }.let { Serialization.cbor.decodeFromBase64<AnonType>(it) }.value(innerSerializer)
                )
                assertEquals(
                    value.value(innerSerializer),
                    Serialization.javaData.encodeToBase64(value).also {
                        println("   javaData wrapped: ${it}")
                        println("   javaData direct:  ${Serialization.javaData.encodeToBase64(value.serializer as KSerializer<Any?>, value.direct!!)}")
                    }.let { Serialization.javaData.decodeFromBase64<AnonType>(it) }.value(innerSerializer)
                )
            }
        }
    }
    @Serializable @GenerateDataClassPaths data class ComplexWithId(override val _id: UUID, val x: Int = 0, val y: String = "", val z: Complex? = null): HasId<UUID>
    @Test fun wsWrapSizing() {
        prepareModelsServerCoreTest()
        TestSettings
        fun value(): AnonType {
            val a = AnonType(
                ModelRestUpdatesWebsocketData(
                    user = RequestAuthSerializable(
                        TestSettings.testUserSubject.handler.name,
                        UUID.random(),
                        UUID.random().toString(),
                        now(),
                        now() + 1.days,
                        scopes = setOf("*")
                    ),
                    condition = Condition.Always,
                    mask = Mask<ComplexWithId>(),
                    topics = setOf("topic/A")
                ),
                ModelRestUpdatesWebsocketData.serializer(ComplexWithId.serializer(), ContextualSerializer(UUID::class))
            )
            val req = WebSocketConnectRequest(
                path = ServerPath("test/test"),
                domain = "domain.com",
                protocol = "https",
                sourceIp = "0.0.0.0",
                headers = HttpHeaders("Authorization" to "something or other")

            )
            val base = QueryParamWebSocketHandlerData(
                req, AnonType(
                    MultiplexWebSocketHandlerState(
                        mapOf(
                            "first" to MultiplexWebSocketHandlerConnectionInfo(
                                storage = a,
                                topics = setOf("topic/A"),
                                request = req
                            ),
                            "first" to MultiplexWebSocketHandlerConnectionInfo(
                                storage = a,
                                topics = setOf("topic/A"),
                                request = req
                            ),
                        ),
                    ),
                    MultiplexWebSocketHandlerState.serializer()
                )
            )
            return AnonType(base, QueryParamWebSocketHandlerData.serializer())
        }

        for(encoding in InternalCommunicationEncoding.values()) {
            println("$encoding")
            engine = LocalEngine(LocalPubSub, encoding)
            val value = value()
            val innerSerializer = value.serializer!!
            println(" ${value.value(innerSerializer)}")
            assertEquals(
                value.value(innerSerializer),
                Serialization.json.encodeToString(value).also {
                    println("   json wrapped: ${it}")
                    println("   json direct:  ${Serialization.json.encodeToString(value.serializer as KSerializer<Any?>, value.direct!!)}")
                }.let { Serialization.json.decodeFromString<AnonType>(it) }.value(innerSerializer)
            )
            assertEquals(
                value.value(innerSerializer),
                Serialization.cbor.encodeToBase64(value).also {
                    println("   cbor wrapped: ${it}")
                    println("   cbor direct:  ${Serialization.cbor.encodeToBase64(value.serializer as KSerializer<Any?>, value.direct!!)}")
                }.let { Serialization.cbor.decodeFromBase64<AnonType>(it) }.value(innerSerializer)
            )
            assertEquals(
                value.value(innerSerializer),
                Serialization.javaData.encodeToBase64(value).also {
                    println("   javaData wrapped: ${it}")
                    println("   javaData direct:  ${Serialization.javaData.encodeToBase64(value.serializer as KSerializer<Any?>, value.direct!!)}")
                }.let { Serialization.javaData.decodeFromBase64<AnonType>(it) }.value(innerSerializer)
            )
        }
    }

    @Serializable data class AnonContainer(val anon: AnonType)
    @Test fun binaryStaysSmall() {
        engine = LocalEngine(LocalPubSub, InternalCommunicationEncoding.JavaData)
        var current = AnonContainer(AnonType(ComplexWithId(UUID.random(), 42, "asdf"), ComplexWithId.serializer()))
        var lastSize = Serialization.javaData.encodeToBase64(AnonContainer.serializer(), current).length
        repeat(20) {
            val data = Serialization.javaData.encodeToBase64(AnonContainer.serializer(), current)
            println(data)
            assertTrue(data.length - lastSize < 10)
            lastSize = data.length
            current = AnonContainer(AnonType(current, AnonContainer.serializer()))
        }
    }
}