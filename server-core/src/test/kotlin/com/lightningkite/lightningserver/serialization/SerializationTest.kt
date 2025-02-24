@file:UseContextualSerialization(Instant::class, UUID::class)
package com.lightningkite.lightningserver.serialization

import com.lightningkite.DeferToContextualUuidSerializer
import com.lightningkite.lightningserver.metrics.roundTo
import com.lightningkite.uuid
import com.lightningkite.now
import org.junit.Assert.*
import org.junit.Test
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds
import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.prepareModelsServerCoreTest
import com.lightningkite.serialization.*
import kotlinx.serialization.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlin.reflect.KClass
import kotlin.test.assertIs
import kotlin.test.assertIsNot

@GenerateDataClassPaths
@Serializable
data class BsonSerTest(
    val x: Int = 42,
    val y: Instant = now().roundTo(1.milliseconds),
    val z: UUID = UUID.random()
)

class SerializationTest {
    @Test fun bson() {
        val v = BsonSerTest()
        println(Serialization.bson.stringify(BsonSerTest.serializer(), v).toJson())
        assertEquals(v, Serialization.bson.load(BsonSerTest.serializer(), Serialization.bson.dump(BsonSerTest.serializer(), v)))
    }
    @OptIn(ExperimentalStdlibApi::class)
    @Test fun protobuf() {
        val v = BsonSerTest(x = -15)
        val asBuffer = Serialization.protobuf.encodeToByteArray(BsonSerTest.serializer(), v)
        println(Serialization.protobuf.schema.generateSchemaText(BsonSerTest.serializer(), "com.lightningkite.lightningserver.serialization"))
        println(asBuffer.toHexString())
        assertEquals(v, Serialization.protobuf.decodeFromByteArray(BsonSerTest.serializer(), asBuffer))
    }
    @OptIn(ExperimentalStdlibApi::class)
    @Test fun protobufPartial() {
        prepareModelsServerCoreTest()
        val v = partialOf<BsonSerTest> {
            it.x assign 15
            it.y assign now().roundTo(1.milliseconds)
            it.z assign UUID.random()
        }
        val s = PartialSerializer(BsonSerTest.serializer())
        val asBuffer = Serialization.protobuf.encodeToByteArray(s, v)
        println(Serialization.protobuf.schema.generateSchemaText(s, "com.lightningkite.lightningserver.serialization"))
        println(asBuffer.toHexString())
        assertEquals(v, Serialization.protobuf.decodeFromByteArray(s, asBuffer))
    }
    @OptIn(ExperimentalStdlibApi::class)
    @Test fun javaData() {
        val v = BsonSerTest(x = -15)
        val asBuffer = Serialization.javaData.encodeToByteArray(BsonSerTest.serializer(), v)
        println(asBuffer.toHexString())
        assertEquals(v, Serialization.javaData.decodeFromByteArray(BsonSerTest.serializer(), asBuffer))
    }
    @OptIn(ExperimentalStdlibApi::class)
    @Test fun javaDataPartial() {
        prepareModelsServerCoreTest()
        val v = partialOf<BsonSerTest> {
            it.x assign 15
            it.y assign now().roundTo(1.milliseconds)
            it.z assign UUID.random()
        }
        val s = PartialSerializer(BsonSerTest.serializer())
        val asBuffer = Serialization.javaData.encodeToByteArray(s, v)
        println(asBuffer.toHexString())
        assertEquals(v, Serialization.javaData.decodeFromByteArray(s, asBuffer))
    }
    @Test fun contextual() {
        assertIs<ContextualSerializer<*>>(Serialization.module.contextualSerializerIfHandled<UUID>())
        assertIs<ContextualSerializer<*>>(Serialization.module.contextualSerializerIfHandled<UUID?>().nullElement())
        assertIs<ContextualSerializer<*>>(Serialization.module.contextualSerializerIfHandled<List<UUID>>().listElement())
        ClientModule.dumpTo(object: SerializersModuleCollector {
            override fun <T : Any> contextual(
                kClass: KClass<T>,
                provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>
            ) {
                if(kClass.typeParameters.isEmpty()) {
                    println("${kClass} -> ${provider(listOf())}")
                }
            }

            override fun <Base : Any, Sub : Base> polymorphic(
                baseClass: KClass<Base>,
                actualClass: KClass<Sub>,
                actualSerializer: KSerializer<Sub>
            ) {
//                println("$baseClass -> $")
            }

            override fun <Base : Any> polymorphicDefaultDeserializer(
                baseClass: KClass<Base>,
                defaultDeserializerProvider: (className: String?) -> DeserializationStrategy<Base>?
            ) {
//                println("$baseClass -> $")
            }

            override fun <Base : Any> polymorphicDefaultSerializer(
                baseClass: KClass<Base>,
                defaultSerializerProvider: (value: Base) -> SerializationStrategy<Base>?
            ) {
//                println("$baseClass -> $")
            }
        })
        assertIs<InstantIso8601Serializer>(ClientModule.getContextual<Instant>())
        assertIs<InstantIso8601Serializer>(ClientModule.serializerPreferContextual<Instant>())
        assertIsNot<ContextualSerializer<*>>(ClientModule.contextualSerializerIfHandled<Int>())
        assertIs<ContextualSerializer<*>>(ClientModule.contextualSerializerIfHandled<Instant>())
        assertIs<DeferToContextualUuidSerializer>(EmptySerializersModule().contextualSerializerIfHandled<UUID>())
        assertIs<InstantIso8601Serializer>(ClientModule.getContextual(ContextualSerializer(Instant::class)))
    }
}