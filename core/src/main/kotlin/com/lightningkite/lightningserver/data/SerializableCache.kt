package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.data.SerializableCache.CalculatingKey
import com.lightningkite.lightningserver.data.SerializableCache.Key
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * A type-safe, serializable cache that can persist across requests or server restarts.
 *
 * Stores values keyed by string IDs, with optional expiration and local-only modes.
 * Values are serialized to bytes for persistence and can be automatically calculated
 * on cache misses using [CalculatingKey].
 *
 * The cache maintains two layers:
 * - In-memory cache of deserialized objects
 * - Serialized byte representation for persistence
 *
 * The [SCOPE] type parameter restricts which keys the cache accepts: a [Key] scoped to `S` can only be used with a
 * cache whose scope is `S` or a subtype of `S`. Keys scoped to `Any?` work with every cache.
 *
 * Example:
 * ```kotlin
 * val cache = SerializableCache<Any?>()
 * val userKey = SerializableCache.Key<Any?, User>("user", User.serializer(), expireAfter = 5.minutes)
 *
 * with(serverRuntime) {
 *     cache[userKey] = currentUser
 *     val user = cache[userKey]  // Retrieve from cache
 * }
 * ```
 *
 * **Important**: This cache is designed to be attached to objects like [Request] via the [Caching]
 * interface, providing request-scoped caching with optional persistence.
 *
 * Safe for concurrent use. Concurrent [getOrPut]s or [CalculatingKey] retrievals of one key calculate it once.
 */
@Serializable(SerializableCache.Serializer::class)
public class SerializableCache<out SCOPE> private constructor(
    private val serialized: ConcurrentHashMap<String, ByteArray>,
    private val cache: ConcurrentHashMap<String, KeyAndResult<*>> = ConcurrentHashMap()
) {
    internal constructor(serialized: Map<String, ByteArray>) : this(ConcurrentHashMap(serialized))

    /** Creates an empty cache. */
    public constructor() : this(ConcurrentHashMap())

    // One lock per key being calculated, so a calculation runs once rather than once per concurrent caller.
    // Not carried by copies: a copy shares no in-flight calculations with its source.
    private val calculating = ConcurrentHashMap<String, Mutex>()

    /**
     * A cache key that identifies a cached value.
     *
     * @param SCOPE The most general cache scope this key may be used in
     * @param T The type of value stored under this key
     */
    public interface Key<in SCOPE, T> {
        /** Unique identifier for this cache entry. Must be unique across all keys. */
        public val id: String

        /** Serializer for converting the value to/from bytes. */
        public val serializer: KSerializer<T>

        /** Optional expiration duration. Null means the value never expires. */
        public val expireAfter: Duration? get() = null

        /**
         * If true, this value is only cached in memory and won't be serialized for persistence.
         * Useful for values that shouldn't or can't be serialized.
         */
        public val localOnly: Boolean get() = false
    }

    /**
     * A cache key that can automatically calculate its value on cache miss.
     *
     * When the cache doesn't contain this key, the [calculate] function is invoked
     * to compute the value, which is then cached for future retrievals.
     *
     * @param SCOPE The most general cache scope this key may be used in
     * @param INPUT The input type needed to calculate the value
     * @param T The type of value stored/calculated
     */
    public interface CalculatingKey<in SCOPE, INPUT, T> : Key<SCOPE, T> {
        /**
         * Calculates the value for this key given the input.
         *
         * Called automatically when the value is not in the cache.
         */
        context(server: ServerRuntime)
        public suspend fun calculate(input: INPUT): T
    }

    private data class KeyAndResult<T>(val key: Key<*, T>, val result: Expiring<T>)

    /**
     * Indicates whether the cache has been modified since creation or last clear.
     *
     * Useful for determining if the cache needs to be persisted.
     */
    @Volatile
    public var updated: Boolean = false
        private set

    /**
     * Retrieves a cached value with expiration checking.
     *
     * Checks both in-memory cache and serialized storage.
     * Expired values are automatically removed.
     *
     * @return The cached Expiring wrapper, or null if not found or expired
     */
    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    context(server: Engine)
    internal fun <T> retrieve(key: Key<*, T>): Expiring<T>? {
        cache[key.id]?.let {
            if (it.key != key) throw IllegalStateException("SerializableCache encountered keys with duplicate ids. ID: ${key.id}")

            if (!it.result.expired) return it.result as Expiring<T>
            // Removed only if unchanged, so a value set concurrently survives. The matching bytes are
            // left for the decode below, which removes them only if they too are unchanged.
            cache.remove(key.id, it)
        }

        val bytes = serialized[key.id] ?: return null
        val decoded = server.internalSerialization.kotlinBytesFormat.decodeFromByteArray(
            Expiring.serializer(key.serializer),
            bytes
        )
        if (decoded.expired) {
            serialized.remove(key.id, bytes)
            return null
        }

        // A concurrent set may have landed while decoding; its value wins over these older bytes.
        val winner = cache.putIfAbsent(key.id, KeyAndResult(key, decoded)) ?: return decoded
        if (winner.key != key) throw IllegalStateException("SerializableCache encountered keys with duplicate ids. ID: ${key.id}")
        return winner.result as Expiring<T>
    }

    /**
     * Stores a value in the cache with the given key.
     *
     * If the key is not local-only, the value is serialized for persistence.
     * Sets the [updated] flag to true.
     */
    context(server: Engine)
    private fun <T> cache(key: Key<*, T>, value: T) {
        val expiring = Expiring(value, expireAfter = key.expireAfter)
        val bytes = if (key.localOnly) null else server.internalSerialization.kotlinBytesFormat
            .encodeToByteArray(Expiring.serializer(key.serializer), expiring)
        // The entry and its bytes are written together under the key's lock in `cache`, so concurrent
        // writers cannot leave them disagreeing.
        cache.compute(key.id) { _, _ ->
            if (bytes != null) serialized[key.id] = bytes
            KeyAndResult(key, expiring)
        }
        updated = true
    }

    /**
     * Stores a value in the cache.
     *
     * @param key The cache key
     * @param value The value to cache
     */
    context(server: Engine)
    public operator fun <T> set(key: Key<SCOPE, T>, value: T): Unit = cache(key, value)

    /**
     * Retrieves a value from the cache.
     *
     * @param key The cache key
     * @return The cached value, or null if not found or expired
     */
    context(server: Engine)
    public operator fun <T> get(key: Key<SCOPE, T>): T? = retrieve(key)?.value

    /**
    * Retrieves a value from the cache, calling [default] if the key is not present.
    * */
    context(server: Engine)
    public inline fun <T, R : T> getOrElse(key: Key<SCOPE, T>, default: () -> R): T = retrieve(key)?.value ?: default()

    /**
     * Retrieves the value for [key], storing the result of [default] if there is none.
     *
     * Concurrent callers for the same key wait for a single run of [default]. [default] must not
     * retrieve [key] itself.
     */
    context(server: Engine)
    public suspend fun <T> getOrPut(key: Key<SCOPE, T>, default: suspend () -> T): T {
        // Checked as an entry, not a value, so a cached null is a hit rather than a recalculation.
        retrieve(key)?.let { return it.value }
        return calculating.getOrPut(key.id) { Mutex() }.withLock {
            retrieve(key)?.let { return@withLock it.value }
            default().also { cache(key, it) }
        }
    }

    /**
     * Retrieves or calculates a value using a calculating key.
     *
     * If the value is in the cache and not expired, returns it.
     * Otherwise, calculates it using the key's calculate function and caches the result.
     *
     * @param key The calculating key
     * @param input The input needed for calculation
     * @return The cached or newly calculated value
     */
    context(server: ServerRuntime)
    public suspend fun <INPUT, T> get(key: CalculatingKey<SCOPE, INPUT, T>, input: INPUT): T =
        getOrPut(key) { key.calculate(input) }

    /**
     * Checks if the cache contains a non-expired value for the given key.
     *
     * @param key The cache key to check
     * @return true if the key has a non-expired value, false otherwise
     */
    context(server: Engine)
    public fun containsKey(key: Key<SCOPE, *>): Boolean = retrieve(key) != null

    internal val bytes: Map<String, ByteArray> get() = serialized.toMap()

    override fun equals(other: Any?): Boolean = other is SerializableCache<*> && run {
        val a = this.bytes
        val b = other.bytes
        if (a.size != b.size) return false
        for ((key, value) in a) {
            if (b[key]?.let { it contentEquals value } != true) return false
        }
        return true
    }

    override fun hashCode(): Int = bytes.hashCode()

    /**
     * Returns a human-readable string representation of the cache contents.
     *
     * Shows decoded values from the in-memory cache and "ENCODED" for serialized-only entries.
     */
    override fun toString(): String = cache
        .map { entry ->
            entry.key to entry.value.result.let { if (it.expiresAt == null) it.value else it }
        }
        .plus((serialized.keys - cache.keys).map { it to "ENCODED" })
        .joinToString(prefix = "{", separator = ", ", postfix = "}") { "${it.first}=${it.second}" }

//    /**
//     * Clears all cached values (both in-memory and serialized) and resets the updated flag.
//     */
//    public fun clear() {
//        cache.clear()
//        serialized.clear()
//        updated = false
//    }

    public fun copy(): SerializableCache<SCOPE> = SerializableCache(
        ConcurrentHashMap(serialized),
        ConcurrentHashMap(cache)
    )

    public companion object {
        private data class KeyData<SCOPE, T>(
            override val id: String,
            override val serializer: KSerializer<T>,
            override val expireAfter: Duration? = null,
            override val localOnly: Boolean = false,
        ) : Key<SCOPE, T>

        /**
         * Creates a simple cache key.
         *
         * @param id Unique identifier for this cache entry
         * @param serializer Serializer for the value type
         * @param expireAfter Optional expiration duration
         * @param localOnly If true, value won't be serialized for persistence
         * @return A new Key instance
         */
        public fun <SCOPE, T> Key(
            id: String,
            serializer: KSerializer<T>,
            expireAfter: Duration? = null,
            localOnly: Boolean = false,
        ): Key<SCOPE, T> = KeyData(id, serializer, expireAfter, localOnly)
    }

    public class Serializer<SCOPE>(ignored: KSerializer<SCOPE>) : KSerializer<SerializableCache<SCOPE>> {
        private val defer = MapSerializer(String.serializer(), ByteArraySerializer())

        override val descriptor: SerialDescriptor
            get() = SerialDescriptor("com.lightningkite.lightningserver.data.SerializableCache", defer.descriptor)

        override fun serialize(encoder: Encoder, value: SerializableCache<SCOPE>) {
            encoder.encodeSerializableValue(defer, value.serialized)
        }

        override fun deserialize(decoder: Decoder): SerializableCache<SCOPE> =
            SerializableCache(decoder.decodeSerializableValue(defer))
    }
}

/**
 * Interface for objects that have an attached [SerializableCache].
 *
 * This is typically implemented by request-like objects to provide
 * request-scoped caching.
 */
public interface Caching<out SCOPE> {
    public val cache: SerializableCache<SCOPE>
}

/**
 * Stores a value in the cache of this Caching object.
 */
context(server: Engine)
public operator fun <SCOPE, T> Caching<SCOPE>.set(key: Key<SCOPE, T>, value: T): Unit = cache.set(key, value)

/**
 * Retrieves a value from the cache of this Caching object.
 */
context(server: Engine)
public operator fun <SCOPE, T> Caching<SCOPE>.get(key: Key<SCOPE, T>): T? = cache[key]

/**
 * Retrieves or calculates a value from the cache of this Caching object.
 */
context(server: ServerRuntime)
public suspend fun <SCOPE, INPUT, T> Caching<SCOPE>.get(key: CalculatingKey<SCOPE, INPUT, T>, input: INPUT): T = cache.get(key, input)

/*
 * TODO: API Recommendations for SerializableCache.kt
 *
 * 1. Consider adding a remove() method to explicitly invalidate cache entries:
 *    - fun remove(key: Key<*>): Boolean
 *
 * 2. Add bulk operations for efficiency:
 *    - fun removeAll(predicate: (String) -> Boolean)
 *    - fun getAll(keys: List<Key<*>>): Map<String, Any?>
 *
 * 3. The equals() implementation could be expensive for large caches due to contentEquals
 *    on every ByteArray. Consider caching hash codes or using a different approach.
 *
 * 4. Add size/statistics methods to help with debugging and monitoring:
 *    - val size: Int (number of cached entries)
 *    - val memorySize: Long (approximate size in bytes)
 *    - fun getStats(): CacheStats (hit/miss ratios, etc.)
 *
 * 5. Consider adding a max size limit with eviction policy (LRU, LFU) to prevent
 *    unbounded growth in long-running applications.
 *
 * 6. The Key interface could benefit from a validation method to ensure id uniqueness
 *    at compile time or startup rather than at runtime during retrieval.
 *
 * 7. Add a typed CalculatingKey factory method similar to the Key factory for consistency
 */
