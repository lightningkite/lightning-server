package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.data.SerializableCache.CalculatingKey
import com.lightningkite.lightningserver.data.SerializableCache.Key
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration


@Serializable(SerializableCache.Serializer::class)
public class SerializableCache private constructor(
    private val serialized: HashMap<String, ByteArray>,
) {
    internal constructor(serialized: Map<String, ByteArray>) : this(HashMap(serialized))

    public constructor() : this(HashMap())

    public interface Key<T> {
        public val id: String
        public val serializer: KSerializer<T>
        public val expireAfter: Duration? get() = null
        public val localOnly: Boolean get() = false
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

    context(server: ServerRuntime)
    public operator fun <T> set(key: Key<T>, value: T): Unit = cache(key, value)

    context(server: ServerRuntime)
    public operator fun <T> get(key: Key<T>): T? = retrieve(key)?.value

    context(server: ServerRuntime)
    public suspend fun <INPUT, T> get(key: CalculatingKey<INPUT, T>, input: INPUT): T =
        retrieve(key)?.value ?: key.calculate(input).also { cache(key, it) }

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

    override fun toString(): String = cache
        .map { entry ->
            entry.key to entry.value.result.let { if (it.expiresAt == null) it.value else it }
        }
        .plus((serialized.keys - cache.keys).map { it to "ENCODED" })
        .joinToString(prefix = "{", separator = ", ", postfix = "}") { "${it.first}=${it.second}" }

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

        public fun <T> Key(
            id: String,
            serializer: KSerializer<T>,
            expireAfter: Duration? = null,
            localOnly: Boolean = false,
        ): Key<T> = KeyData(id, serializer, expireAfter, localOnly)
    }

    private object Serializer : KSerializer<SerializableCache> {
        private val defer = MapSerializer(String.serializer(), ByteArraySerializer())

        override val descriptor: SerialDescriptor
            get() = SerialDescriptor("com.lightningkite.lightningserver.SerializableCache", defer.descriptor)

        override fun serialize(encoder: Encoder, value: SerializableCache) {
            defer.serialize(encoder, value.bytes)
        }

        override fun deserialize(decoder: Decoder): SerializableCache =
            SerializableCache(decoder.decodeSerializableValue(defer))
    }
}

context(server: ServerRuntime)
public inline fun <T> SerializableCache.getOrPut(key: Key<T>, default: () -> T): T =
    get(key) ?: default().also { set(key, it) }


public interface Caching {
    public val cache: SerializableCache
}

context(server: ServerRuntime)
public operator fun <T> Caching.set(key: Key<T>, value: T): Unit = cache.set(key, value)

context(server: ServerRuntime)
public operator fun <T> Caching.get(key: Key<T>): T? = cache[key]

context(server: ServerRuntime)
public suspend fun <INPUT, T> Caching.get(key: CalculatingKey<INPUT, T>, input: INPUT): T = cache.get(key, input)