@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlin.reflect.KProperty1

@Serializable(DataClassPathSerializer::class)
abstract class DataClassPathPartial<K> {
    abstract fun getAny(key: K): Any?
    abstract fun setAny(key: K, any: Any?): K
    abstract val properties: List<KProperty1<*, *>>
}

abstract class DataClassPath<K, V>: DataClassPathPartial<K>() {
    abstract fun get(key: K): V?
    abstract fun set(key: K, value: V): K
    @Suppress("UNCHECKED_CAST")
    override fun getAny(key: K) = get(key)
    @Suppress("UNCHECKED_CAST")
    override fun setAny(key: K, any: Any?) = set(key, any as V)
    abstract fun mapCondition(condition: Condition<V>): Condition<K>
    abstract fun mapModification(modification: Modification<V>): Modification<K>
}

class DataClassPathSelf<K>(): DataClassPath<K, K>() {
    override fun get(key: K): K? = key
    override fun set(key: K, value: K): K = value
    override fun toString(): String = "this"
    override fun hashCode(): Int = 0
    override fun equals(other: Any?): Boolean = other is DataClassPathSelf<*>
    override val properties: List<KProperty1<*, *>> get() = listOf()
    override fun mapCondition(condition: Condition<K>): Condition<K> = condition
    override fun mapModification(modification: Modification<K>): Modification<K> = modification
}
data class DataClassPathAccess<K, M, V>(val first: DataClassPath<K, M>, val second: KProperty1<M, V>): DataClassPath<K, V>() {
    override fun get(key: K): V? = first.get(key)?.let { second.get(it) }
    override fun set(key: K, value: V): K = first.get(key)?.let { first.set(key, second.setCopy(it, value)) } ?: key
    override fun toString(): String = if(first is DataClassPathSelf<*>) second.name else "$first.${second.name}"
    override val properties: List<KProperty1<*, *>> get() = first.properties + listOf(second)
    override fun mapCondition(condition: Condition<V>): Condition<K> = first.mapCondition(Condition.OnField(second, condition))
    override fun mapModification(modification: Modification<V>): Modification<K> = first.mapModification(Modification.OnField(second, modification))
}
data class DataClassPathNotNull<K, V>(val wraps: DataClassPath<K, V?>): DataClassPath<K, V>() {
    override val properties: List<KProperty1<*, *>>
        get() = wraps.properties

    override fun get(key: K): V? = wraps.get(key)
    override fun set(key: K, value: V): K = wraps.set(key, value)
    override fun toString(): String = "$wraps?"
    override fun mapCondition(condition: Condition<V>): Condition<K> = wraps.mapCondition(Condition.IfNotNull(condition))
    override fun mapModification(modification: Modification<V>): Modification<K> = wraps.mapModification(Modification.IfNotNull(modification))
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

operator fun <K, V, V2> DataClassPath<K, V>.get(prop: KProperty1<V, V2>) = DataClassPathAccess(this, prop)
val <K, V> DataClassPath<K, V?>.notNull: DataClassPathNotNull<K, V> get() = DataClassPathNotNull(this)

class DataClassPathSerializer<T>(val inner: KSerializer<T>): KSerializer<DataClassPathPartial<T>> {
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
        override val annotations: List<Annotation> = DataClassPathPartial::class.annotations
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
            val prop = KProperty1Parser[currentSerializer](name)
            currentSerializer = prop.second
            val c = current
            @Suppress("UNCHECKED_CAST")
            current = if(c == null) DataClassPathAccess(DataClassPathSelf<T>(), prop.first as KProperty1<T, Any?>)
            else DataClassPathAccess(c as DataClassPath<T, Any?>, prop.first as KProperty1<Any?, Any?>)
            if(part.endsWith('?')) {
                current = DataClassPathNotNull(current as DataClassPath<T, Any?>)
                currentSerializer = currentSerializer.nullElement()!!
            }
        }

        return current ?: DataClassPathSelf()
    }
}