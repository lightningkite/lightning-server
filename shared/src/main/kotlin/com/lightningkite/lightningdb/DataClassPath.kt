@file:SharedCode
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import kotlinx.serialization.ExperimentalSerializationApi
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
abstract class DataClassPathPartial<K : IsCodableAndHashable> : Hashable {
    abstract fun getAny(key: K): Any?
    abstract fun setAny(key: K, any: Any?): K
    abstract val properties: List<KProperty1<*, *>>
    abstract override fun hashCode(): Int
    abstract override fun toString(): String
    abstract override fun equals(other: Any?): Boolean
}

abstract class DataClassPath<K : IsCodableAndHashable, V : IsCodableAndHashable> : DataClassPathPartial<K>() {
    abstract fun get(key: K): V?
    abstract fun set(key: K, value: V): K

    @Suppress("UNCHECKED_CAST")
    override fun getAny(key: K): Any? = get(key)

    @Suppress("UNCHECKED_CAST")
    override fun setAny(key: K, any: Any?): K = set(key, any as V)
    abstract fun mapCondition(condition: Condition<V>): Condition<K>
    abstract fun mapModification(modification: Modification<V>): Modification<K>

    @JsName("prop")
    operator fun <V2> get(prop: KProperty1<V, V2>) = DataClassPathAccess(this, prop)
}

class DataClassPathSelf<K : IsCodableAndHashable>() : DataClassPath<K, K>() {
    override fun get(key: K): K? = key
    override fun set(key: K, value: K): K = value
    override fun toString(): String = "this"
    override fun hashCode(): Int = 0
    override fun equals(other: Any?): Boolean = other is DataClassPathSelf<*>
    override val properties: List<KProperty1<*, *>> get() = listOf()
    override fun mapCondition(condition: Condition<K>): Condition<K> = condition
    override fun mapModification(modification: Modification<K>): Modification<K> = modification
}

data class DataClassPathAccess<K : IsCodableAndHashable, M : IsCodableAndHashable, V : IsCodableAndHashable>(
    val first: DataClassPath<K, M>,
    val second: KProperty1<M, V>
) : DataClassPath<K, V>() {
    override fun get(key: K): V? = first.get(key)?.let {
        try {
            second.get(it)
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not get $second on ${it}")
        }
    }

    override fun set(key: K, value: V): K = first.get(key)?.let { first.set(key, second.setCopy(it, value)) } ?: key
    override fun toString(): String = if (first is DataClassPathSelf<*>) second.name else "$first.${second.name}"
    override val properties: List<KProperty1<*, *>> get() = first.properties + listOf(second)
    override fun mapCondition(condition: Condition<V>): Condition<K> =
        first.mapCondition(Condition.OnField(second, condition))

    override fun mapModification(modification: Modification<V>): Modification<K> =
        first.mapModification(Modification.OnField(second, modification))
}

data class DataClassPathNotNull<K : IsCodableAndHashable, V : IsCodableAndHashable>(val wraps: DataClassPath<K, V?>) :
    DataClassPath<K, V>() {
    override val properties: List<KProperty1<*, *>>
        get() = wraps.properties

    override fun get(key: K): V? = wraps.get(key)
    override fun set(key: K, value: V): K = wraps.set(key, value)
    override fun toString(): String = "$wraps?"
    override fun mapCondition(condition: Condition<V>): Condition<K> =
        wraps.mapCondition(Condition.IfNotNull(condition))

    override fun mapModification(modification: Modification<V>): Modification<K> =
        wraps.mapModification(Modification.IfNotNull(modification))
}

data class DataClassPathList<K : IsCodableAndHashable, V : IsCodableAndHashable>(val wraps: DataClassPath<K, List<V>>) :
    DataClassPath<K, V>() {
    override val properties: List<KProperty1<*, *>>
        get() = wraps.properties

    override fun get(key: K): V? = wraps.get(key)?.firstOrNull()
    override fun set(key: K, value: V): K = wraps.set(key, listOf(value))
    override fun toString(): String = "$wraps.*"
    override fun mapCondition(condition: Condition<V>): Condition<K> =
        wraps.mapCondition(Condition.ListAllElements(condition))

    override fun mapModification(modification: Modification<V>): Modification<K> =
        wraps.mapModification(Modification.ListPerElement(Condition.Always(), modification))
}

data class DataClassPathSet<K : IsCodableAndHashable, V : IsCodableAndHashable>(val wraps: DataClassPath<K, Set<V>>) :
    DataClassPath<K, V>() {
    override val properties: List<KProperty1<*, *>>
        get() = wraps.properties

    override fun get(key: K): V? = wraps.get(key)?.firstOrNull()
    override fun set(key: K, value: V): K = wraps.set(key, setOf(value))
    override fun toString(): String = "$wraps.*"
    override fun mapCondition(condition: Condition<V>): Condition<K> =
        wraps.mapCondition(Condition.SetAllElements(condition))

    override fun mapModification(modification: Modification<V>): Modification<K> =
        wraps.mapModification(Modification.SetPerElement(Condition.Always(), modification))
}

@JsName("notNull")
val <K : IsCodableAndHashable, V : IsCodableAndHashable> DataClassPath<K, V?>.notNull: DataClassPathNotNull<K, V>
    get() = DataClassPathNotNull(
        this
    )

@JsName("listElements")
@get:JvmName("getListElements")
val <K : IsCodableAndHashable, V : IsCodableAndHashable> DataClassPath<K, List<V>>.elements: DataClassPathList<K, V>
    get() = DataClassPathList(
        this
    )

@JsName("setElements")
@get:JvmName("getSetElements")
val <K : IsCodableAndHashable, V : IsCodableAndHashable> DataClassPath<K, Set<V>>.elements: DataClassPathSet<K, V>
    get() = DataClassPathSet(
        this
    )
