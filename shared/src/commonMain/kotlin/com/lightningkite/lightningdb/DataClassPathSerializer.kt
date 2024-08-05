@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import com.lightningkite.lightningdb.SerializableProperty
import kotlinx.serialization.SerializationException


private class SerializablePropertyParser<T>(val serializer: KSerializer<T>) {
    val children = run {
        serializer.serializableProperties!!.associateBy {it.name }
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

class DataClassPathSerializer<T>(val inner: KSerializer<T>): KSerializer<DataClassPathPartial<T>> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = object: SerialDescriptor {
        override val kind: SerialKind = PrimitiveKind.STRING
        override val serialName: String = "com.lightningkite.lightningdb.DataClassPathPartial"
        override val elementsCount: Int get() = 0
        override fun getElementName(index: Int): String = error()
        override fun getElementIndex(name: String): Int = error()
        override fun isElementOptional(index: Int): Boolean = error()
        override fun getElementDescriptor(index: Int): SerialDescriptor = error()
        override fun getElementAnnotations(index: Int): List<Annotation> = error()
        override fun toString(): String = "PrimitiveDescriptor($serialName)"
        private fun error(): Nothing = throw IllegalStateException("Primitive descriptor does not have elements")
        override val annotations: List<Annotation> = listOf()
    }

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
        for(part in value.split('.')) {
            val name = part.removeSuffix("?")
            if(name == "this") continue
            val prop = try{ SerializablePropertyParser[currentSerializer](name) } catch (e:IllegalStateException) { throw SerializationException(message = e.message, cause = e)}
            currentSerializer = prop.serializer
            val c = current
            @Suppress("UNCHECKED_CAST")
            current = if(c == null) DataClassPathAccess(DataClassPathSelf<T>(inner), prop as SerializableProperty<T, Any?>)
            else DataClassPathAccess(c as DataClassPath<T, Any?>, prop as SerializableProperty<Any?, Any?>)
            if(part.endsWith('?')) {
                @Suppress("UNCHECKED_CAST")
                current = DataClassPathNotNull(current as DataClassPath<T, Any?>)
                currentSerializer = currentSerializer.nullElement()!!
            }
        }

        return current ?: DataClassPathSelf(inner)
    }
}
