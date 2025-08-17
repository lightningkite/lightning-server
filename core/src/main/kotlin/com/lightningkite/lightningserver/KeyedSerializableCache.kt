package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Instant


@Serializable(KeyedSerializableCacheSerializer::class)
public class KeyedSerializableCache<INPUT> internal constructor(
    private val serialized: HashMap<String, ByteArray>
) {
    public constructor() : this(HashMap())
    internal constructor(serialized: Map<String, ByteArray>) : this(HashMap(serialized))

    public interface Key<INPUT, T> {
        public val id: String
        public val serializer: KSerializer<T>

        context(server: ServerRuntime)
        public suspend fun calculate(input: INPUT): T

        public val expireAfter: Duration? get() = null
    }

    @Serializable
    private data class Expiring<T>(
        val value: T,
        @Contextual val expires: Instant?
    ) {
        context(server: ServerRuntime)
        val expired get() = expires != null && expires <= server.clock.now()
    }

    private data class KeyAndResult<INPUT, T>(val key: Key<INPUT, T>, val result: Expiring<T>)

    private val cache = HashMap<String, KeyAndResult<INPUT, *>>()

    public var updated: Boolean = false
        private set

    context(server: ServerRuntime)
    public suspend fun <T> get(key: Key<INPUT, T>, input: INPUT): T {
        cache[key.id]?.let {
            if (it.key !== key) throw IllegalStateException("KeyedSerializableCache encountered keys with duplicate ids. ID: ${key.id}, Key1: ${it.key}, Key2: $key")
        }

        @Suppress("UNCHECKED_CAST")
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

        val calculated = key.calculate(input)
        val expiring = Expiring(
            calculated,
            expires = key.expireAfter?.let { server.clock.now() + it }
        )

        serialized[key.id] = server.internalSerialization.kotlinBytesFormat.encodeToByteArray(Expiring.serializer(key.serializer), expiring)
        cache[key.id] = KeyAndResult(key, expiring)
        updated = true

        return calculated
    }

    internal val bytes: Map<String, ByteArray> get() = serialized.toMap()

    override fun equals(other: Any?): Boolean = other is KeyedSerializableCache<*> && run {
        val a = this.bytes
        val b = other.bytes
        if(a.size != b.size) return false
        for((key, value) in a) {
            if(!b.containsKey(key) || !value.contentEquals(b[key]!!)) return false
        }
        return true
    }
    override fun hashCode(): Int = bytes.hashCode()
    override fun toString(): String = cache.toString()

    public fun clear() {
        cache.clear()
        serialized.clear()
        updated = false
    }
}

public object KeyedSerializableCacheSerializer : KSerializer<KeyedSerializableCache<*>> {
    private val defer = MapSerializer(String.serializer(), ByteArraySerializer())

    override val descriptor: SerialDescriptor
        get() = SerialDescriptor("com.lightningkite.lightningserver.KeyedSerializableCache", defer.descriptor)

    override fun serialize(
        encoder: Encoder,
        value: KeyedSerializableCache<*>
    ) {
       defer.serialize(encoder, value.bytes)
    }

    override fun deserialize(decoder: Decoder): KeyedSerializableCache<*> = KeyedSerializableCache<Any?>(decoder.decodeSerializableValue(defer))
}