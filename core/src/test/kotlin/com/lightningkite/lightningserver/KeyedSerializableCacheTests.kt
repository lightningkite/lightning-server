package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test

class KeyedSerializableCacheTests {
    object Server : ServerBuilder()

    @Test
    fun testSerialization() {
        val cache = KeyedSerializableCache<Int>()

        var calculated = false

        val key = object : KeyedSerializableCache.Key<Int, String> {
            override val id: String = "key"
            override val serializer: KSerializer<String> = String.serializer()

            context(server: ServerRuntime)
            override suspend fun calculate(input: Int): String {
                if (calculated) throw IllegalStateException("Recalculating")
                calculated = true
                return input.toString()
            }
        }

        runBlocking {
            Server.test({}) {
                // need a runtime for caching

                val str = cache.get(key, 5)

                val serialized = json.encodeToString(cache)
                val deserialized = json.decodeFromString<KeyedSerializableCache<Int>>(serialized)

                deserialized.get(key, 5)
            }
        }
    }

    @Test
    fun disallowsDuplicateKeys() {
        val cache = KeyedSerializableCache<Int>()

        val key = object : KeyedSerializableCache.Key<Int, String> {
            override val id: String = "key"
            override val serializer: KSerializer<String> = String.serializer()

            context(server: ServerRuntime)
            override suspend fun calculate(input: Int): String = input.toString()
        }

        val key2 = object : KeyedSerializableCache.Key<Int, String> {
            override val id: String = "key"
            override val serializer: KSerializer<String> = String.serializer()

            context(server: ServerRuntime)
            override suspend fun calculate(input: Int): String = input.toString()
        }

        runBlocking {
            Server.test({}) {
                // need a runtime for caching

                cache.get(key, 5)

                var thrown = false
                try {
                    cache.get(key2, 5)
                } catch (_: IllegalStateException) {
                    thrown = true
                }
                if (!thrown) throw Exception("Did not fail as expected")
            }
        }
    }
}