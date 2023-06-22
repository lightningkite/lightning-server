@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlin.reflect.KProperty1

@Serializable(KeyPathSerializer::class)
abstract class KeyPathPartial<K> {
    abstract fun getAny(key: K): Any?
    abstract fun setAny(key: K, any: Any?): K
    abstract val properties: List<KProperty1<*, *>>
}

abstract class KeyPath<K, V>: KeyPathPartial<K>() {
    abstract fun get(key: K): V
    abstract fun set(key: K, value: V): K
    @Suppress("UNCHECKED_CAST")
    override fun getAny(key: K) = get(key)
    @Suppress("UNCHECKED_CAST")
    override fun setAny(key: K, any: Any?) = set(key, any as V)
}

class KeyPathSelf<K>(): KeyPath<K, K>() {
    override fun get(key: K): K = key
    override fun set(key: K, value: K): K = value
    override fun toString(): String = "this"
    override fun hashCode(): Int = 0
    override fun equals(other: Any?): Boolean = other is KeyPathSelf<*>
    override val properties: List<KProperty1<*, *>> get() = listOf()
}
data class KeyPathAccess<K, M, V>(val first: KeyPath<K, M>, val second: KProperty1<M, V>): KeyPath<K, V>() {
    override fun get(key: K): V = first.get(key).let { second.get(it) }
    override fun set(key: K, value: V): K = first.set(key, second.setCopy(first.get(key), value))
    override fun toString(): String = if(first is KeyPathSelf<*>) second.name else "$first.${second.name}"
    override val properties: List<KProperty1<*, *>> get() = first.properties + listOf(second)
}
data class KeyPathSafeAccess<K, M: Any, V>(val first: KeyPath<K, M?>, val second: KProperty1<M, V>): KeyPath<K, V?>() {
    override fun get(key: K): V? = first.get(key)?.let { second.get(it) }
    override fun set(key: K, value: V?): K = first.get(key)?.let {
        @Suppress("UNCHECKED_CAST")
        first.set(key, second.setCopy(it, value as V))
    } ?: key
    override fun toString(): String = "$first?.${second.name}"
    override val properties: List<KProperty1<*, *>> get() = first.properties + listOf(second)
}
data class KeyPathNotNull<K, M: Any>(val source: KeyPath<K, M?>): KeyPath<K, M>() {
    override fun get(key: K): M = source.get(key)!!
    override fun set(key: K, value: M): K = source.set(key, value)
    override fun toString(): String = "$source!!"
    override val properties: List<KProperty1<*, *>> get() = source.properties
}

@OptIn(InternalSerializationApi::class)
private class KProperty1Parser<T>(val serializer: KSerializer<T>) {
    val children = run {
        val c = (serializer as GeneratedSerializer<T>).childSerializers().withIndex()
            .associate { serializer.descriptor.getElementName(it.index) to it.value }
        serializer.attemptGrabFields().mapValues {
            it.value to c[it.key]!!
        }
    }
    companion object {
        val existing = HashMap<KSerializer<*>, KProperty1Parser<*>>()
        @Suppress("UNCHECKED_CAST")
        operator fun <T> get(serializer: KSerializer<T>): KProperty1Parser<T> = existing.getOrPut(serializer) {
            KProperty1Parser(serializer)
        } as KProperty1Parser<T>
    }
    operator fun invoke(key: String): Pair<KProperty1<T, *>, KSerializer<*>> {
        @Suppress("UNCHECKED_CAST")
        return children[key]
            ?: throw IllegalStateException("Could find no property with name '$key' on ${serializer.descriptor.serialName}")
    }
}

operator fun <K, V, V2> KeyPath<K, V>.get(prop: KProperty1<V, V2>) = KeyPathAccess(this, prop)
fun <K, V: Any, V2> KeyPath<K, V?>.getSafe(prop: KProperty1<V, V2>) = KeyPathSafeAccess(this, prop)

class KeyPathSerializer<T>(val inner: KSerializer<T>): KSerializer<KeyPathPartial<T>> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = object: SerialDescriptor {
        override val kind: SerialKind = PrimitiveKind.STRING
        override val serialName: String = "SortPart<${inner.descriptor.serialName}>"
        override val elementsCount: Int get() = 0
        override fun getElementName(index: Int): String = error()
        override fun getElementIndex(name: String): Int = error()
        override fun isElementOptional(index: Int): Boolean = error()
        override fun getElementDescriptor(index: Int): SerialDescriptor = error()
        override fun getElementAnnotations(index: Int): List<Annotation> = error()
        override fun toString(): String = "PrimitiveDescriptor($serialName)"
        private fun error(): Nothing = throw IllegalStateException("Primitive descriptor does not have elements")
        override val annotations: List<Annotation> = KeyPathPartial::class.annotations
    }

    override fun deserialize(decoder: Decoder): KeyPathPartial<T> {
        val value = decoder.decodeString()
        return fromString(value)
    }

    override fun serialize(encoder: Encoder, value: KeyPathPartial<T>) {
        encoder.encodeString(value.toString())
    }

    fun fromString(value: String): KeyPathPartial<T> {
        var current: KeyPathPartial<T>? = null
        var currentSerializer: KSerializer<*> = inner
        var isNullable = false
        for(part in value.split('.')) {
            val name = part.removeSuffix("?")
            if(name == "this") continue
            val prop = KProperty1Parser[currentSerializer](name)
            currentSerializer = prop.second
            val c = current
            @Suppress("UNCHECKED_CAST")
            current = if(c == null) KeyPathAccess(KeyPathSelf<T>(), prop.first as KProperty1<T, Any?>)
            else if(isNullable) KeyPathSafeAccess(c as KeyPath<T, Any?>, prop.first as KProperty1<Any, Any?>)
            else KeyPathAccess(c as KeyPath<T, Any?>, prop.first as KProperty1<Any?, Any?>)
            isNullable = part.endsWith('?')
            if(isNullable) currentSerializer = currentSerializer.nullElement()!!
        }

        return current ?: KeyPathSelf()
    }
}