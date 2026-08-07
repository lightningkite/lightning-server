package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.route
import com.lightningkite.services.cache.Cache
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * CacheExamplesEndpoints - Demonstrates caching patterns and operations.
 *
 * These examples showcase:
 * - Basic cache operations (get, set, delete)
 * - Cache expiration and TTL
 * - Cache-aside pattern for expensive operations
 * - Cache invalidation strategies
 * - Integration with different cache backends (Redis, Memcached, DynamoDB, RAM)
 */
class CacheExamplesEndpoints(
    private val cache: Runtime<Cache>,
) : ServerBuilder() {

    /**
     * POST /cache/set
     * 
     * Store a value in the cache with optional expiration.
     * Demonstrates: Basic cache write operation with TTL
     */
    val setCache = path.path("cache").path("set").post bind ApiHttpHandler(
        summary = "Store a value in the cache",
        description = "Saves a key-value pair in the cache with optional expiration time",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { input: SetCacheRequest ->
            cache().set(
                input.key,
                input.value,
                String.serializer(),
                input.expireSeconds?.seconds
            )

            SetCacheResponse(
                success = true,
                message = "Value cached successfully",
                key = input.key,
                expiresIn = input.expireSeconds
            )
        }
    )

    /**
     * GET /cache/get/{key}
     * 
     * Retrieve a value from the cache.
     * Demonstrates: Basic cache read operation
     */
    val getCache = path.path("cache").path("get").arg<String>("key").get bind ApiHttpHandler(
        summary = "Retrieve a value from the cache",
        description = "Fetches a cached value by key, returns null if not found or expired",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            val key = route.arg1
            val value = cache().get(key, String.serializer())

            GetCacheResponse(
                key = key,
                value = value,
                found = value != null
            )
        }
    )

    /**
     * DELETE /cache/delete/{key}
     * 
     * Remove a value from the cache.
     * Demonstrates: Cache invalidation
     */
    val deleteCache =
        path.path("cache").path("delete").arg<String>("key").delete bind ApiHttpHandler<_, Nothing?, Unit, Unit>(
            summary = "Delete a cached value",
            description = "Removes a key-value pair from the cache",
            auth = noAuth,
            successCode = HttpStatus.NoContent,
            implementation = { _: Unit ->
                val key = route.arg1
                cache().remove(key)
            }
        )

    /**
     * GET /cache/expensive-operation/{id}
     * 
     * Demonstrates the cache-aside pattern for expensive operations.
     * First checks cache, if not found, performs expensive operation and caches result.
     */
    val expensiveOperation = path.path("cache").path("expensive-operation").arg<String>("id").get bind ApiHttpHandler(
        summary = "Expensive operation with caching",
        description = "Demonstrates cache-aside pattern: checks cache first, performs expensive operation if cache miss, then caches result",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            val id = route.arg1
            val cacheKey = "expensive-op:$id"

            // Try to get from cache first
            val cached = cache().get(cacheKey, ExpensiveOperationResult.serializer())

            if (cached != null) {
                return@ApiHttpHandler cached.copy(
                    message = "Retrieved from cache (fast!)"
                )
            }

            // Cache miss - perform expensive operation
            println("Cache miss for $cacheKey - performing expensive operation")
            val startTime = System.currentTimeMillis()

            // Simulate expensive database query or computation
            delay(2000) // 2 second delay

            val result = ExpensiveOperationResult(
                id = id,
                data = "Computed result for $id: ${Random.nextInt(1000, 9999)}",
                computationTimeMs = System.currentTimeMillis() - startTime,
                message = "Computed and cached (slow)"
            )

            // Cache the result for 5 minutes
            cache().set(cacheKey, result, ExpensiveOperationResult.serializer(), 5.minutes)

            result
        }
    )

    /**
     * POST /cache/increment/{key}
     *
     * Demonstrates atomic increment operation.
     * Useful for counters, rate limiting, etc.
     */
    val incrementCounter = path.path("cache").path("increment").arg<String>("key").post bind ApiHttpHandler(
        summary = "Increment a counter in the cache",
        description = "Atomically increments a numeric value in the cache, useful for counters and rate limiting",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { input: IncrementRequest ->
            val key = route.arg1

            // Cache.add() is atomic (read-then-write is not: two concurrent requests would lose
            // an increment). It returns the value *after* incrementing.
            val newValue = cache().add(key, input.incrementBy, input.expireSeconds?.seconds)

            IncrementResponse(
                key = key,
                previousValue = newValue - input.incrementBy,
                newValue = newValue,
                incrementedBy = input.incrementBy
            )
        }
    )

    /**
     * POST /cache/batch-set
     * 
     * Set multiple cache entries at once.
     * Demonstrates: Batch operations for efficiency
     */
    val batchSet = path.path("cache").path("batch-set").post bind ApiHttpHandler(
        summary = "Set multiple cache entries at once",
        description = "Efficiently stores multiple key-value pairs in the cache",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { input: BatchSetRequest ->
            var count = 0
            input.entries.forEach { entry ->
                cache().set(
                    entry.key,
                    entry.value,
                    String.serializer(),
                    input.defaultExpireSeconds?.seconds
                )
                count++
            }

            BatchSetResponse(
                success = true,
                entriesSet = count
            )
        }
    )

    // No pattern-based clear endpoint: the Cache interface has no `keys(pattern)` or
    // `removePattern()` operation (see the TODO in Cache.kt), so there is no real, backend-agnostic
    // way to implement one. A version of this endpoint that just returned entriesCleared = 0 without
    // clearing anything used to live here; shipping an endpoint that lies about what it did is worse
    // than not having it.
}

// Request/Response models

@Serializable
data class SetCacheRequest(
    val key: String,
    val value: String,
    val expireSeconds: Int? = null,
)

@Serializable
data class SetCacheResponse(
    val success: Boolean,
    val message: String,
    val key: String,
    val expiresIn: Int?,
)

@Serializable
data class GetCacheResponse(
    val key: String,
    val value: String?,
    val found: Boolean,
)

@Serializable
data class ExpensiveOperationResult(
    val id: String,
    val data: String,
    val computationTimeMs: Long,
    val message: String,
)

@Serializable
data class IncrementRequest(
    val incrementBy: Int = 1,
    val expireSeconds: Int? = null,
)

@Serializable
data class IncrementResponse(
    val key: String,
    val previousValue: Int,
    val newValue: Int,
    val incrementedBy: Int,
)

@Serializable
data class CacheEntry(
    val key: String,
    val value: String,
)

@Serializable
data class BatchSetRequest(
    val entries: List<CacheEntry>,
    val defaultExpireSeconds: Int? = null,
)

@Serializable
data class BatchSetResponse(
    val success: Boolean,
    val entriesSet: Int,
)
