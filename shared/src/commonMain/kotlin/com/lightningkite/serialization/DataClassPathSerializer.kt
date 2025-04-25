@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor


private class SerializablePropertyParser<T>(val serializer: KSerializer<T>) {
    val children = run {
        (serializer.serializableProperties ?: throw SerializationException("${serializer.descriptor.serialName} does not have any serializable properties")).associateBy {it.name }
    }
    companion object {
        val existing = HashMap<KSerializerKey, SerializablePropertyParser<*>>()
        @Suppress("UNCHECKED_CAST")
        operator fun <T> get(serializer: KSerializer<T>): SerializablePropertyParser<T> = existing.getOrPut(KSerializerKey(serializer)) {
            SerializablePropertyParser(serializer)
        } as SerializablePropertyParser<T>
    }
    operator fun invoke(key: String): SerializableProperty<T, *> {
        @Suppress("UNCHECKED_CAST")
        return children[key]
            ?: throw IllegalStateException("Could find no property with name '$key' on ${serializer.descriptor.serialName}")
    }
}

class DataClassPathSerializer<T>(val inner: KSerializer<T>): KSerializerWithDefault<DataClassPathPartial<T>> {
    override val default: DataClassPathPartial<T>
        get() = DataClassPathSelf(inner)
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.db.DataClassPathPartial", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DataClassPathPartial<T> {
        val value = decoder.decodeString()
        return fromString(value)
    }

    override fun serialize(encoder: Encoder, value: DataClassPathPartial<T>) {
        encoder.encodeString(value.toString())
    }

    fun fromString(value: String): DataClassPathPartial<T> {
        var current: DataClassPathPartial<T>? = null
        var currentSerializer: KSerializer<*> = inner
        val valueParts = value.split('.')
        for((index, part) in valueParts.withIndex()) {
            val name = part.removeSuffix("?")
            if(name == "this") continue
            val prop = try{
                SerializablePropertyParser[currentSerializer](name)
            } catch (e:IllegalStateException) {
                throw SerializationException(message = e.message, cause = e)
            }
            currentSerializer = prop.serializer
            val c = current
            @Suppress("UNCHECKED_CAST")
            current = if(c == null) DataClassPathAccess(DataClassPathSelf<T>(inner), prop as SerializableProperty<T, Any?>)
            else DataClassPathAccess(c as DataClassPath<T, Any?>, prop as SerializableProperty<Any?, Any?>)
            if(part.endsWith('?') || prop.serializer.descriptor.isNullable && index != valueParts.lastIndex) {
                @Suppress("UNCHECKED_CAST")
                current = DataClassPathNotNull(current as DataClassPath<T, Any?>)
                currentSerializer = currentSerializer.nullElement() ?: throw SerializationException("${prop.name} is not nullable")
            }
        }

        return current ?: DataClassPathSelf(inner)
    }
}
