@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.*
import com.lightningkite.khrysalis.*
import kotlinx.serialization.*

@Serializable(ModificationSerializer::class)
sealed class Modification<T: IsCodableAndHashable>  {
    abstract operator fun invoke(on: T): T

    @Deprecated("Use the modification {} builder instead")
    infix fun then(other: Modification<T>): Modification.Chain<T> = Modification.Chain(listOf(this, other))

    @Serializable(ModificationChainSerializer::class)
    data class Chain<T: IsCodableAndHashable>(val modifications: List<Modification<T>>): Modification<T>() {
        override fun invoke(on: T): T = modifications.fold(on) { item, mod -> mod(item) }
    }

    @Serializable(ModificationIfNotNullSerializer::class)
    data class IfNotNull<T: IsCodableAndHashable>(val modification: Modification<T>): Modification<T?>() {
        override fun invoke(on: T?): T? = on?.let { modification(it) }
    }

    @Serializable(ModificationAssignSerializer::class)
    data class Assign<T: IsCodableAndHashable>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = value
    }

    @Serializable(ModificationCoerceAtMostSerializer::class)
    data class CoerceAtMost<T: ComparableCodableAndHashable<T>>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = on.coerceAtMost(value)
    }

    @Serializable(ModificationCoerceAtLeastSerializer::class)
    data class CoerceAtLeast<T: ComparableCodableAndHashable<T>>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = on.coerceAtLeast(value)
    }

    @Serializable(ModificationIncrementSerializer::class)
    data class Increment<T: Number>(val by: T): Modification<T>() {
        override fun invoke(on: T): T = on + by
    }

    @Serializable(ModificationMultiplySerializer::class)
    data class Multiply<T: Number>(val by: T): Modification<T>() {
        override fun invoke(on: T): T = on * by
    }

    @Serializable(ModificationAppendStringSerializer::class)
    data class AppendString(val value: String): Modification<String>() {
        override fun invoke(on: String): String = on + value
    }

    @Serializable(ModificationAppendRawStringSerializer::class)
    data class AppendRawString<T: IsRawString>(val value: String): Modification<T>() {
        override fun invoke(on: T): T = on.mapRaw { it + value } as T
    }

    @Serializable(ModificationListAppendSerializer::class)
    data class ListAppend<T: IsCodableAndHashable>(val items: List<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on + items
    }

    @Serializable(ModificationListRemoveSerializer::class)
    data class ListRemove<T: IsCodableAndHashable>(val condition: Condition<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.filter { !condition(it) }
    }

    @Serializable(ModificationListRemoveInstancesSerializer::class)
    data class ListRemoveInstances<T: IsCodableAndHashable>(val items: List<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on - items
    }

    @Serializable(ModificationListDropFirstSerializer::class)
    class ListDropFirst<T: IsCodableAndHashable>: Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.drop(1)
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.ListDropFirst<T>) != null
    }

    @Serializable(ModificationListDropLastSerializer::class)
    class ListDropLast<T: IsCodableAndHashable>: Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.dropLast(1)
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.ListDropLast<T>) != null
    }

    @Serializable()
    @SerialName("ListPerElement")
    data class ListPerElement<T: IsCodableAndHashable>(val condition: Condition<T>, val modification: Modification<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.map { if(condition(it)) modification(it) else it }
    }

    @Serializable(ModificationSetAppendSerializer::class)
    data class SetAppend<T: IsCodableAndHashable>(val items: Set<T>): Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = (on + items)
    }

    @Serializable(ModificationSetRemoveSerializer::class)
    data class SetRemove<T: IsCodableAndHashable>(val condition: Condition<T>): Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.filter { !condition(it) }.toSet()
    }

    @Serializable(ModificationSetRemoveInstancesSerializer::class)
    data class SetRemoveInstances<T: IsCodableAndHashable>(val items: Set<T>): Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on - items
    }

    @Serializable(ModificationSetDropFirstSerializer::class)
    class SetDropFirst<T: IsCodableAndHashable>: Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.drop(1).toSet()
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.SetDropFirst<T>) != null
    }

    @Serializable(ModificationSetDropLastSerializer::class)
    class SetDropLast<T: IsCodableAndHashable>: Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.toList().dropLast(1).toSet()
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.SetDropLast<T>) != null
    }

    @Serializable()
    @SerialName("SetPerElement")
    data class SetPerElement<T: IsCodableAndHashable>(val condition: Condition<T>, val modification: Modification<T>): Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.map { if(condition(it)) modification(it) else it }.toSet()
    }

    @Serializable(ModificationCombineSerializer::class)
    data class Combine<T: IsCodableAndHashable>(val map: Map<String, T>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on + map
    }

    @Serializable(ModificationModifyByKeySerializer::class)
    data class ModifyByKey<T: IsCodableAndHashable>(val map: Map<String, Modification<T>>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on + map.mapValues { (on[it.key]?.let { e -> it.value(e) } ?: throw Exception()) }
    }

    @Serializable(ModificationRemoveKeysSerializer::class)
    data class RemoveKeys<T: IsCodableAndHashable>(val fields: Set<String>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on.filterKeys { it !in fields }
    }

    data class OnField<K: IsCodableAndHashable, V: IsCodableAndHashable>(val key: SerializableProperty<K, V>, val modification: Modification<V>): Modification<K>() {
        override fun invoke(on: K): K = key.setCopy(on, modification(key.get(on)))
    }
}
