package com.lightningkite.lightningdb

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.properties.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class KSerializerKeyTest {
    val myJson = Json {
        serializersModule = ClientModule
    }
    val myProperties = Properties(ClientModule)

    init {
        com.lightningkite.lightningdb.prepareModels()
    }

    /**
     * Prevents an issue seen recently with mis-merging items.
     */
    @Test
    fun unique() {
        val models = listOf(
            User.serializer(),
            Post.serializer(),
            Employee.serializer(),
            EmbeddedObjectTest.serializer(),
            ClassUsedForEmbedding.serializer(),
            RecursiveEmbed.serializer(),
            EmbeddedNullable.serializer(),
            LargeTestModel.serializer(),
            EmbeddedMap.serializer(),
            Cursed.serializer(),
            String.serializer(),
            Char.serializer(),
            Byte.serializer(),
            Short.serializer(),
            Int.serializer(),
            Long.serializer(),
            Float.serializer(),
            Double.serializer(),
            Boolean.serializer(),
        )
        val serializers = models.flatMap {
            listOf(
                it,
                it.nullable,
                ListSerializer(it),
                MapSerializer(String.serializer(), it),
                Condition.serializer(it),
                Modification.serializer(it)
            )
        }
        val failures = serializers.groupBy { KSerializerKey(it) }.filterValues { it.size > 1 }
        failures.forEach { println(it) }
        assertEquals(0, failures.size)
    }

    @Test fun notUnique() {
        assertEquals(KSerializerKey(ListSerializer(User.serializer())), KSerializerKey(ListSerializer(User.serializer())))
    }
}