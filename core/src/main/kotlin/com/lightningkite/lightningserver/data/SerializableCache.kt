package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.data.SerializableCache.CalculatingKey
import com.lightningkite.lightningserver.data.SerializableCache.Key
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.collections.iterator
import kotlin.time.Duration
import kotlin.time.Instant


@Serializable(SerializableCacheSerializer::class)
public class SerializableCache private constructor(
    private val serialized: HashMap<String, ByteArray>
) {
    internal constructor(serialized: Map<String, ByteArray>) : this(HashMap(serialized))

    public constructor() : this(HashMap())

    public interface Key<T> {
        public val id: String
        public val serializer: KSerializer<T>
        public val expireAfter: Duration? get() = null
    }

    public interface CalculatingKey<INPUT, T> : Key<T> {
        context(server: ServerRuntime)
        public suspend fun calculate(input: INPUT): T
    }

    private data class KeyAndResult<T>(val key: Key<T>, val result: Expiring<T>)

    private val cache = HashMap<String, KeyAndResult<*>>()

    public var updated: Boolean = false
        private set

    context(server: ServerRuntime)
    private fun <T> retrieve(key: Key<T>): T? {
        cache[key.id]?.let {
            if (it.key !== key) throw IllegalStateException("KeyedSerializableCache encountered keys with duplicate ids. ID: ${key.id}")
        }

        cache[key.id]
            ?.takeUnless { it.result.expired }
            ?.let { return it.result.value as T }

        serialized[key.id]
            ?.let { server.internalSerialization.kotlinBytesFormat.decodeFromByteArray(Expiring.serializer(key.serializer), it) }
            ?.takeUnless { it.expired }
            ?.let {
                cache[key.id] = KeyAndResult(key, it)
                return it.value
            }

        return null
    }

    context(server: ServerRuntime)
    private fun <T> cache(key: Key<T>, value: T) {
        val expiring = Expiring(
            value,
            expires = key.expireAfter?.let { server.clock.now() + it }
        )

        serialized[key.id] = server.internalSerialization.kotlinBytesFormat.encodeToByteArray(Expiring.serializer(key.serializer), expiring)
        cache[key.id] = KeyAndResult(key, expiring)
        updated = true
    }

    context(server: ServerRuntime)
    public operator fun <T> set(key: Key<T>, value: T): Unit = cache(key, value)

    context(server: ServerRuntime)
    public operator fun <T> get(key: Key<T>): T? = retrieve(key)

    context(server: ServerRuntime)
    public suspend fun <INPUT, T> get(key: CalculatingKey<INPUT, T>, input: INPUT): T =
        retrieve(key) ?: key.calculate(input).also { cache(key, it) }


    internal val bytes: Map<String, ByteArray> get() = serialized.toMap()

    override fun equals(other: Any?): Boolean = other is SerializableCache && run {
        val a = this.bytes
        val b = other.bytes
        if (a.size != b.size) return false
        for ((key, value) in a) {
            if (!b.containsKey(key) || !value.contentEquals(b[key]!!)) return false
        }
        return true
    }

    override fun hashCode(): Int = bytes.hashCode()
    override fun toString(): String = cache
        .map { entry ->
            entry.key to entry.value.result.let { if (it.expires == null) it.value else it }
        }
        .plus((serialized.keys - cache.keys).map { it to "ENCODED" })
        .joinToString(prefix = "{", separator = ", ", postfix = "}") { "${it.first}=${it.second}" }

    public fun clear() {
        cache.clear()
        serialized.clear()
        updated = false
    }
}

public interface Caching {
    public val cache: SerializableCache
}

context(server: ServerRuntime)
public operator fun <T : Any> Caching.set(key: Key<T>, value: T): Unit = cache.set(key, value)

context(server: ServerRuntime)
public operator fun <T : Any> Caching.get(key: Key<T>): T? = cache[key]

context(server: ServerRuntime)
public suspend fun <INPUT, T : Any> Caching.get(key: CalculatingKey<INPUT, T>, input: INPUT): T = cache.get(key, input)


public object SerializableCacheSerializer : KSerializer<SerializableCache> {
    private val defer = MapSerializer(String.serializer(), ByteArraySerializer())

    override val descriptor: SerialDescriptor
        get() = SerialDescriptor("com.lightningkite.lightningserver.KeyedSerializableCache", defer.descriptor)

    override fun serialize(encoder: Encoder, value: SerializableCache) { defer.serialize(encoder, value.bytes) }
    override fun deserialize(decoder: Decoder): SerializableCache = SerializableCache(decoder.decodeSerializableValue(defer))
}