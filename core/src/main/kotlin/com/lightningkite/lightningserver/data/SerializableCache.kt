package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.data.SerializableCache.CalculatingKey
import com.lightningkite.lightningserver.data.SerializableCache.Key
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
 * Example:
 * ```kotlin
 * val cache = SerializableCache()
 * val userKey = SerializableCache.Key("user", User.serializer(), expireAfter = 5.minutes)
 *
 * with(serverRuntime) {
 *     cache[userKey] = currentUser
 *     val user = cache[userKey]  // Retrieve from cache
 * }
 * ```
 *
 * **Important**: This cache is designed to be attached to objects like [Request] via the [Caching]
 * interface, providing request-scoped caching with optional persistence.
 */
@Serializable(SerializableCache.Serializer::class)
public class SerializableCache private constructor(
    private val serialized: HashMap<String, ByteArray>,
) {
    internal constructor(serialized: Map<String, ByteArray>) : this(HashMap(serialized))

    /** Creates an empty cache. */
    public constructor() : this(HashMap())

    /**
     * A cache key that identifies a cached value.
     *
     * @param T The type of value stored under this key
     */
    public interface Key<T> {
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
     * @param INPUT The input type needed to calculate the value
     * @param T The type of value stored/calculated
     */
    public interface CalculatingKey<INPUT, T> : Key<T> {
        /**
         * Calculates the value for this key given the input.
         *
         * Called automatically when the value is not in the cache.
         */
        context(server: ServerRuntime)
        public suspend fun calculate(input: INPUT): T
    }

    private data class KeyAndResult<T>(val key: Key<T>, val result: Expiring<T>)

    private val cache = HashMap<String, KeyAndResult<*>>()

    /**
     * Indicates whether the cache has been modified since creation or last clear.
     *
     * Useful for determining if the cache needs to be persisted.
     */
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
    context(server: ServerRuntime)
    private fun <T> retrieve(key: Key<T>): Expiring<T>? {
        @Suppress("UNCHECKED_CAST")
        cache[key.id]?.let {
            if (it.key != key) throw IllegalStateException("SerializableCache encountered keys with duplicate ids. ID: ${key.id}")

            if (it.result.expired) {
                cache.remove(key.id)
                serialized.remove(key.id)
                return null
            }

            return it.result as Expiring<T>
        }

        serialized[key.id]
            ?.let {
                server.internalSerialization.kotlinBytesFormat.decodeFromByteArray(
                    Expiring.serializer(key.serializer),
                    it
                )
            }
            ?.let {
                if (it.expired) {
                    serialized.remove(key.id)
                    return null
                }

                cache[key.id] = KeyAndResult(key, it)
                return it
            }

        return null
    }

    /**
     * Stores a value in the cache with the given key.
     *
     * If the key is not local-only, the value is serialized for persistence.
     * Sets the [updated] flag to true.
     */
    context(server: ServerRuntime)
    private fun <T> cache(key: Key<T>, value: T) {
        val expiring = Expiring(
            value,
            expireAfter = key.expireAfter
        )

        if (!key.localOnly)
            serialized[key.id] = server.internalSerialization.kotlinBytesFormat
                .encodeToByteArray(Expiring.serializer(key.serializer), expiring)

        cache[key.id] = KeyAndResult(key, expiring)
        updated = true
    }

    /**
     * Stores a value in the cache.
     *
     * @param key The cache key
     * @param value The value to cache
     */
    context(server: ServerRuntime)
    public operator fun <T> set(key: Key<T>, value: T): Unit = cache(key, value)

    /**
     * Retrieves a value from the cache.
     *
     * @param key The cache key
     * @return The cached value, or null if not found or expired
     */
    context(server: ServerRuntime)
    public operator fun <T> get(key: Key<T>): T? = retrieve(key)?.value

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
    public suspend fun <INPUT, T> get(key: CalculatingKey<INPUT, T>, input: INPUT): T =
        retrieve(key)?.value ?: key.calculate(input).also { cache(key, it) }

    /**
     * Checks if the cache contains a non-expired value for the given key.
     *
     * @param key The cache key to check
     * @return true if the key has a non-expired value, false otherwise
     */
    context(server: ServerRuntime)
    public fun containsKey(key: Key<*>): Boolean = retrieve(key) != null

    internal val bytes: Map<String, ByteArray> get() = serialized.toMap()

    override fun equals(other: Any?): Boolean = other is SerializableCache && run {
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

    /**
     * Clears all cached values (both in-memory and serialized) and resets the updated flag.
     */
    public fun clear() {
        cache.clear()
        serialized.clear()
        updated = false
    }

    public companion object {
        private data class KeyData<T>(
            override val id: String,
            override val serializer: KSerializer<T>,
            override val expireAfter: Duration? = null,
            override val localOnly: Boolean = false,
        ) : Key<T>

        /**
         * Creates a simple cache key.
         *
         * @param id Unique identifier for this cache entry
         * @param serializer Serializer for the value type
         * @param expireAfter Optional expiration duration
         * @param localOnly If true, value won't be serialized for persistence
         * @return A new Key instance
         */
        public fun <T> Key(
            id: String,
            serializer: KSerializer<T>,
            expireAfter: Duration? = null,
            localOnly: Boolean = false,
        ): Key<T> = KeyData(id, serializer, expireAfter, localOnly)
    }

    public object Serializer : KSerializer<SerializableCache> {
        private val defer = MapSerializer(String.serializer(), ByteArraySerializer())

        override val descriptor: SerialDescriptor
            get() = SerialDescriptor("com.lightningkite.lightningserver.data.SerializableCache", defer.descriptor)

        override fun serialize(encoder: Encoder, value: SerializableCache) {
            defer.serialize(encoder, value.bytes)
        }

        override fun deserialize(decoder: Decoder): SerializableCache =
            SerializableCache(decoder.decodeSerializableValue(defer))
    }
}

/**
 * Retrieves a cached value or computes and caches it if missing.
 *
 * This is similar to Map's getOrPut but works with the ServerRuntime context.
 *
 * @param key The cache key
 * @param default Function to compute the value if not cached
 * @return The cached or newly computed value
 */
context(server: ServerRuntime)
public inline fun <T> SerializableCache.getOrPut(key: Key<T>, default: () -> T): T =
    get(key) ?: default().also { set(key, it) }

/**
 * Interface for objects that have an attached [SerializableCache].
 *
 * This is typically implemented by request-like objects to provide
 * request-scoped caching.
 */
public interface Caching {
    public val cache: SerializableCache
}

/**
 * Stores a value in the cache of this Caching object.
 */
context(server: ServerRuntime)
public operator fun <T> Caching.set(key: Key<T>, value: T): Unit = cache.set(key, value)

/**
 * Retrieves a value from the cache of this Caching object.
 */
context(server: ServerRuntime)
public operator fun <T> Caching.get(key: Key<T>): T? = cache[key]

/**
 * Retrieves or calculates a value from the cache of this Caching object.
 */
context(server: ServerRuntime)
public suspend fun <INPUT, T> Caching.get(key: CalculatingKey<INPUT, T>, input: INPUT): T = cache.get(key, input)

/*
 * TODO: API Recommendations for SerializableCache.kt
 *
 * 1. Add thread-safety documentation - is this cache safe for concurrent access?
 *    If not, consider adding synchronization or documenting usage constraints.
 *
 * 2. Consider adding a remove() method to explicitly invalidate cache entries:
 *    - fun remove(key: Key<*>): Boolean
 *
 * 3. Add bulk operations for efficiency:
 *    - fun removeAll(predicate: (String) -> Boolean)
 *    - fun getAll(keys: List<Key<*>>): Map<String, Any?>
 *
 * 4. The equals() implementation could be expensive for large caches due to contentEquals
 *    on every ByteArray. Consider caching hash codes or using a different approach.
 *
 * 5. Add size/statistics methods to help with debugging and monitoring:
 *    - val size: Int (number of cached entries)
 *    - val memorySize: Long (approximate size in bytes)
 *    - fun getStats(): CacheStats (hit/miss ratios, etc.)
 *
 * 6. Consider adding a max size limit with eviction policy (LRU, LFU) to prevent
 *    unbounded growth in long-running applications.
 *
 * 7. The Key interface could benefit from a validation method to ensure id uniqueness
 *    at compile time or startup rather than at runtime during retrieval.
 *
 * 8. Add a typed CalculatingKey factory method similar to the Key factory for consistency
 */
