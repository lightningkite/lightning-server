package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.demo.endpoints.*
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheExamplesEndpointsTest {

    @Test
    fun setThenGetRoundTrips() = runBlocking {
        TestHelper.testServer {
            Server.cacheExamples.setCache.test(null, SetCacheRequest(key = "greeting", value = "hello"))

            val result = Server.cacheExamples.getCache.test("greeting", null, Unit)

            assertTrue(result.found)
            assertEquals("hello", result.value)
        }
    }

    @Test
    fun getMissingKeyReturnsNotFound() = runBlocking {
        TestHelper.testServer {
            val result = Server.cacheExamples.getCache.test("nonexistent-key", null, Unit)

            assertFalse(result.found)
            assertNull(result.value)
        }
    }

    @Test
    fun deleteRemovesTheKey() = runBlocking {
        TestHelper.testServer {
            Server.cacheExamples.setCache.test(null, SetCacheRequest(key = "temp", value = "gone-soon"))
            Server.cacheExamples.deleteCache.test("temp", null, Unit)

            val result = Server.cacheExamples.getCache.test("temp", null, Unit)
            assertFalse(result.found)
        }
    }

    @Test
    fun incrementIsAtomicAndReturnsPreviousAndNewValue() = runBlocking {
        TestHelper.testServer {
            val first = Server.cacheExamples.incrementCounter.test("hits", null, IncrementRequest(incrementBy = 1))
            assertEquals(0, first.previousValue)
            assertEquals(1, first.newValue)

            val second = Server.cacheExamples.incrementCounter.test("hits", null, IncrementRequest(incrementBy = 5))
            assertEquals(1, second.previousValue)
            assertEquals(6, second.newValue)
        }
    }

    @Test
    fun batchSetStoresEveryEntry() = runBlocking {
        TestHelper.testServer {
            val response = Server.cacheExamples.batchSet.test(
                null,
                BatchSetRequest(
                    entries = listOf(
                        CacheEntry("a", "1"),
                        CacheEntry("b", "2"),
                        CacheEntry("c", "3"),
                    )
                )
            )

            assertEquals(3, response.entriesSet)
            assertEquals("2", Server.cacheExamples.getCache.test("b", null, Unit).value)
        }
    }

    @Test
    fun expensiveOperationIsCachedOnSecondCall() = runBlocking {
        TestHelper.testServer {
            val first = Server.cacheExamples.expensiveOperation.test("item-1", null, Unit)
            assertTrue(first.message.contains("Computed"))

            val second = Server.cacheExamples.expensiveOperation.test("item-1", null, Unit)
            assertTrue(second.message.contains("cache"))
            assertEquals(first.data, second.data)
        }
    }
}
