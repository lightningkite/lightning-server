package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SerializableCacheTest {
    object TestServer : ServerBuilder()

    /**
     * A clock the test advances by hand. Expiry is a property of the server's clock, so sleeping a
     * real thread past a short deadline only tests how loaded the machine is: under a full parallel
     * build a single `set` has taken 170ms, longer than the 100ms deadline it was establishing.
     */
    private class MovableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    @Serializable
    data class User(val name: String, val age: Int)

    @Test
    fun testSetAndGet() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key("user", String.serializer())

                cache.set(key, "Alice")
                assertEquals("Alice", cache.get(key))
            }
        }
    }

    @Test
    fun testGetMissing() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key("missing", String.serializer())

                assertNull(cache.get(key))
            }
        }
    }

    @Test
    fun testWithExpiration() {
        val clock = MovableClock(Instant.fromEpochSeconds(1_700_000_000))
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() },
            clock = { clock },
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key(
                    "expiring",
                    String.serializer(),
                    expireAfter = 10.milliseconds
                )

                cache.set(key, "test")
                assertEquals("test", cache.get(key))

                clock.instant += 20.milliseconds

                // Should be expired now
                assertNull(cache.get(key))
            }
        }
    }

    @Test
    fun testLocalOnly() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key(
                    "local",
                    String.serializer(),
                    localOnly = true
                )

                cache.set(key, "test")
                assertEquals("test", cache.get(key))

                // Local-only keys should work but not be serialized
                assertTrue(cache.containsKey(key))
            }
        }
    }

    @Test
    fun testContainsKey() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key("test", String.serializer())

                assertFalse(cache.containsKey(key))
                cache.set(key, "value")
                assertTrue(cache.containsKey(key))
            }
        }
    }

    @Test
    fun testCalculatingKey() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                var calculationCount = 0

                val key = object : SerializableCache.CalculatingKey<String, String> {
                    override val id = "calculated"
                    override val serializer = String.serializer()

                    context(server: ServerRuntime)
                    override suspend fun calculate(input: String): String {
                        calculationCount++
                        return input.uppercase()
                    }
                }

                // First call should calculate
                assertEquals("HELLO", cache.get(key, "hello"))
                assertEquals(1, calculationCount)

                // Second call should use cache
                assertEquals("HELLO", cache.get(key, "hello"))
                assertEquals(1, calculationCount) // Still 1, not recalculated
            }
        }
    }

    @Test
    fun testGetOrPut() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key("test", Int.serializer())
                var computeCount = 0

                val value1 = cache.getOrPut(key) {
                    computeCount++
                    42
                }
                assertEquals(42, value1)
                assertEquals(1, computeCount)

                val value2 = cache.getOrPut(key) {
                    computeCount++
                    99
                }
                assertEquals(42, value2) // Should return cached value
                assertEquals(1, computeCount) // Should not compute again
            }
        }
    }

    @Test
    fun testClear() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key("test", String.serializer())

                cache.set(key, "value")
                assertTrue(cache.containsKey(key))

                cache.clear()
                assertFalse(cache.containsKey(key))
                assertFalse(cache.updated)
            }
        }
    }

    @Test
    fun testUpdatedFlag() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key("test", String.serializer())

                assertFalse(cache.updated)

                cache.set(key, "value")
                assertTrue(cache.updated)

                cache.clear()
                assertFalse(cache.updated)
            }
        }
    }

    @Test
    fun testComplexType() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key("user", User.serializer())
                val user = User("Alice", 30)

                cache.set(key, user)
                assertEquals(user, cache.get(key))
            }
        }
    }

    @Test
    fun testEquality() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache1 = SerializableCache()
                val cache2 = SerializableCache()
                val key = SerializableCache.Key("test", String.serializer())

                assertEquals(cache1, cache2) // Both empty

                cache1.set(key, "value")
                cache2.set(key, "value")

                assertEquals(cache1, cache2) // Same contents
            }
        }
    }

    @Test
    fun testCachingInterface() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                class TestCaching : Caching {
                    override val cache = SerializableCache()
                }

                val caching = TestCaching()
                val key = SerializableCache.Key("test", String.serializer())

                caching.set(key, "value")
                assertEquals("value", caching.get(key))
            }
        }
    }

    @Test
    fun testMultipleKeys() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key1 = SerializableCache.Key("key1", String.serializer())
                val key2 = SerializableCache.Key("key2", Int.serializer())
                val key3 = SerializableCache.Key("key3", User.serializer())

                cache.set(key1, "value1")
                cache.set(key2, 42)
                cache.set(key3, User("Bob", 25))

                assertEquals("value1", cache.get(key1))
                assertEquals(42, cache.get(key2))
                assertEquals(User("Bob", 25), cache.get(key3))
            }
        }
    }

    @Test
    fun testSerializationRoundTrip() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache1 = SerializableCache()
                val key = SerializableCache.Key("test", String.serializer())

                cache1.set(key, "test value")

                // Simulate serialization/deserialization by creating new cache from bytes
                val bytes = cache1.bytes
                val cache2 = SerializableCache(bytes)

                // Should be able to retrieve the value from deserialized cache
                assertEquals("test value", cache2.get(key))
            }
        }
    }

    @Test
    fun testToString() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key("test", String.serializer())

                cache.set(key, "value")

                val str = cache.toString()
                assertNotNull(str)
                assertTrue(str.contains("test"))
            }
        }
    }

    @Test
    fun testExpirationCleansUpCache() {
        val clock = MovableClock(Instant.fromEpochSeconds(1_700_000_000))
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() },
            clock = { clock },
        ) {
            runBlocking {
                val cache = SerializableCache()
                val key = SerializableCache.Key(
                    "expiring",
                    String.serializer(),
                    expireAfter = 100.milliseconds
                )

                cache.set(key, "test")
                assertTrue(cache.containsKey(key))

                clock.instant += 200.milliseconds

                // Accessing expired key should return null and clean up
                assertNull(cache.get(key))
                assertFalse(cache.containsKey(key))
            }
        }
    }
}
