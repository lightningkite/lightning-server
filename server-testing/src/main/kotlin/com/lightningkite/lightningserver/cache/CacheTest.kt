package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class CacheTest {
    abstract val cache: CacheInterface?

    @Test
    fun test() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            val key = "key"
            assertEquals(null, cache.get<Int>(key))
            cache.set(key, 8)
            assertEquals(8, cache.get<Int>(key))
            cache.set(key, 1)
            assertEquals(1, cache.get<Int>(key))
            assertTrue(cache.modify<Int>(key) { it?.plus(1) })
            assertEquals(2, cache.get<Int>(key))
            cache.add(key, 2)
            assertEquals(4, cache.get<Int>(key))
            cache.remove(key)
            assertEquals(null, cache.get<Int>(key))
            cache.setIfNotExists(key, 2)
            cache.setIfNotExists(key, 3)
            assertEquals(2, cache.get<Int>(key))
        }
    }

    @Test
    fun healthCheck() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            assertEquals(HealthStatus.Level.OK, cache.healthCheck().level)
        }
    }
}