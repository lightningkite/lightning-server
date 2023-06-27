@file:SharedCode
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.Hashable
import com.lightningkite.khrysalis.SharedCode
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

operator fun <K, V, V2> DataClassPath<K, V>.get(prop: KProperty1<V, V2>) = DataClassPathAccess(this, prop)
val <K, V> DataClassPath<K, V?>.notNull: DataClassPathNotNull<K, V> get() = DataClassPathNotNull(this)
