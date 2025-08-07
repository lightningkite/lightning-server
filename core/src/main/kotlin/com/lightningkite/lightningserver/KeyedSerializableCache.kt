package com.lightningkite.lightningserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration


@Serializable(KeyedSerializableCacheSerializer::class)
public class KeyedSerializableCache {
    public interface Key<T> {
        public val id: String
        public val serializer: KSerializer<T>
        public suspend fun calculate(serverRunning: ServerRunning, request: Request<*>): T
        public val expireAfter: Duration? get() = null
    }

    internal val cache: HashMap<Key<*>, Any?> = HashMap()
    internal var cacheQuickAccess: Map<String, ByteArray>? = null
    internal var cacheUpdated: Boolean = false
        private set
    internal var serverRunning: ServerRunning? = null
    internal suspend fun <T> get(serverRunning: ServerRunning, request: Request<*>, key: Key<T>): T {
        this.serverRunning = serverRunning
        @Suppress("UNCHECKED_CAST")
        if (cache.containsKey(key)) return cache[key] as T
        val calculated: T = if(cacheQuickAccess?.containsKey(key.id) == true) {
            serverRunning.server.internalSerialization.kotlinBytesFormat.decodeFromByteArray(
                key.serializer,
                cacheQuickAccess!![key.id]!!
            )
        } else key.calculate(serverRunning, request)
        cache[key] = calculated
        cacheUpdated = true
        return calculated
    }

    internal val cacheQuickAccessUpdated: Map<String, ByteArray> get() {
        if(cache.isEmpty() || !cacheUpdated) return cacheQuickAccess ?: emptyMap()
        val newMap = cacheQuickAccess?.toMutableMap() ?: HashMap()
        for((key, value) in cache) {
            key as Key<Any?>
            newMap[key.id] =
                serverRunning!!.server.internalSerialization.kotlinBytesFormat.encodeToByteArray(key.serializer, value)
        }
        return newMap.toMap()
    }

    override fun equals(other: Any?): Boolean = other is KeyedSerializableCache && run {
        val a = this.cacheQuickAccessUpdated
        val b = other.cacheQuickAccessUpdated
        if(a.size != b.size) return false
        for((key, value) in a) {
            if(!b.containsKey(key) || !value.contentEquals(b[key]!!)) return false
        }
        return true
    }
    override fun hashCode(): Int = this.cacheQuickAccessUpdated.hashCode()
    override fun toString(): String = cache.toString()
    public fun clear() {
        cache.clear()
        cacheQuickAccess = null
        cacheUpdated = false
    }
}

public object KeyedSerializableCacheSerializer: KSerializer<KeyedSerializableCache> {
    private val defer = MapSerializer(String.serializer(), ByteArraySerializer())
    override val descriptor: SerialDescriptor
        get() = SerialDescriptor("com.lightningkite.lightningserver.KeyedSerializableCache", defer.descriptor)

    override fun serialize(
        encoder: Encoder,
        value: KeyedSerializableCache
    ) {
        defer.serialize(encoder, value.cacheQuickAccessUpdated)
    }

    override fun deserialize(decoder: Decoder): KeyedSerializableCache = KeyedSerializableCache().apply {
        cacheQuickAccess = decoder.decodeSerializableValue(defer)
    }

}