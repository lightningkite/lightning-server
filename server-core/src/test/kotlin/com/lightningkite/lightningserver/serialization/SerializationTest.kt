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
import com.lightningkite.serialization.*
import kotlinx.serialization.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlin.reflect.KClass
import kotlin.test.assertIs
import kotlin.test.assertIsNot

@Serializable
data class BsonSerTest(
    val x: Int = 42,
    @Contextual val y: Instant = now().roundTo(1.milliseconds),
    @Contextual val z: UUID = uuid()
)

class SerializationTest {
    @Test fun bson() {
        val v = BsonSerTest()
        println(Serialization.bson.stringify(BsonSerTest.serializer(), v).toJson())
        assertEquals(v, Serialization.bson.load(BsonSerTest.serializer(), Serialization.bson.dump(BsonSerTest.serializer(), v)))
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
    }
}