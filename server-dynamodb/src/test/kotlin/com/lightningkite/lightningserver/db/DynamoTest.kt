package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.CacheTest
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

object DynamoForTests {
    var d = DynamoDbCache( { embeddedDynamo() })

}

class DynamoTest : CacheTest() {
    override val cache: Cache?
        get() = DynamoForTests.d

    @Test fun parsing() {
        val target = setOf("asdf", "fdsa")
        val serializer = SetSerializer(String.serializer())
        assertEquals(target, serializer.fromDynamo(serializer.toDynamo(target)))
    }

    @Test override fun expirationTest() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            val key = "x"
            assertEquals(null, cache.get<Int>(key))
            cache.set<Int>(key, 1, 2.seconds)
            assertEquals(1, cache.get<Int>(key))
            delay(3000)
            assertEquals(null, cache.get<Int>(key))
            cache.set<Int>(key, 1, 2.seconds)
            cache.add(key, 1, 2.seconds)
            assertEquals(2, cache.get<Int>(key))
            delay(9000)
            assertEquals(null, cache.get<Int>(key))
            cache.add(key, 1, 2.seconds)
            assertEquals(1, cache.get<Int>(key))
            delay(3000)
            assertEquals(null, cache.get<Int>(key))
        }
        runBlocking {
            val key = "y"
            assertEquals(null, cache.get<Int>(key))
            cache.add(key, 1, 2.seconds)
            assertEquals(1, cache.get<Int>(key))
            delay(3000)
            assertEquals(null, cache.get<Int>(key))
        }
    }
}
