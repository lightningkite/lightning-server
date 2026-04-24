package com.lightningkite.lightningserver.definition

import kotlinx.coroutines.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Tests for thread-safe caching in ServerSetting, Runtime, and RuntimeDeferred.
 *
 * These tests verify that the fix using @Volatile and synchronized blocks
 * ensures that cached values are only computed once even with concurrent access.
 */
class ServerSettingThreadSafetyTest {

    @Test
    fun `Runtime Cached should only execute once with concurrent access`() = runBlocking {
        val executionCount = AtomicInteger(0)

        val runtime = Runtime<String> {
            executionCount.incrementAndGet()
            Thread.sleep(50) // Simulate slow initialization
            "computed-value-${executionCount.get()}"
        }

        val cached = Runtime.Cached(runtime)

        // Create a minimal test runtime context
        val testRuntime = object : com.lightningkite.lightningserver.runtime.ServerRuntime {
            override val server get() = throw NotImplementedError()
            override val settings get() = throw NotImplementedError()
            override val internalSerialization get() = throw NotImplementedError()
            override val externalSerialization get() = throw NotImplementedError()
            override val serverId get() = ""
            override val serverVersion get() = ""
            override val projectName get() = ""
            override val sharedResources get() = throw NotImplementedError()
            override suspend fun <T> com.lightningkite.lightningserver.definition.Task<T>.invoke(input: T) =
                throw NotImplementedError()

            override suspend fun <PATH : com.lightningkite.lightningserver.pathing.PathSpec, T> sendWebSocketSubscriptionMessage(
                event: com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage<PATH, T>,
            ) = throw NotImplementedError()
        }

        // Launch 100 concurrent coroutines trying to access the cached value
        val results = (1..100).map {
            async(Dispatchers.Default) {
                with(testRuntime) {
                    cached()
                }
            }
        }.awaitAll()

        // All results should be the same
        assertEquals(100, results.size)
        results.forEach { assertEquals(results.first(), it) }

        // The computation should only happen once despite 100 concurrent accesses
        assertEquals(1, executionCount.get(), "Runtime should only execute once, got ${executionCount.get()}")
    }
}
