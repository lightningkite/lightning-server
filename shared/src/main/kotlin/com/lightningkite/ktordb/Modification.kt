@file:SharedCode

package com.lightningkite.ktordb

import com.lightningkite.khrysalis.*
import kotlinx.serialization.*

@Serializable(ModificationSerializer::class)
// Why is this class open instead of sealed?  See https://github.com/Kotlin/kotlinx.serialization/issues/1843
open class Modification<T: IsCodableAndHashable> protected constructor()  {
    open override fun hashCode(): Int { fatalError() }
    open override fun equals(other: Any?): Boolean { fatalError() }
    open operator fun invoke(on: T): T { fatalError() }
    open fun invokeDefault(): T { fatalError() }

    infix fun then(other: Modification<T>): Modification.Chain<T> = Modification.Chain(listOf(this, other))

    @Serializable(ModificationChainSerializer::class)
    data class Chain<T: IsCodableAndHashable>(val modifications: List<Modification<T>>): Modification<T>() {
        override fun invoke(on: T): T = modifications.fold(on) { item, mod -> mod(item) }
        override fun invokeDefault(): T {
            val on = modifications[0].invokeDefault()
            return modifications.drop(1).fold(on) { item, mod -> mod(item) }
        }
    }

    @Serializable(ModificationIfNotNullSerializer::class)
    data class IfNotNull<T: IsCodableAndHashable>(val modification: Modification<T>): Modification<T?>() {
        override fun invoke(on: T?): T? = on?.let { modification(it) }
        override fun invokeDefault(): T? = null
    }

    @Serializable(ModificationAssignSerializer::class)
    data class Assign<T: IsCodableAndHashable>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = value
        override fun invokeDefault(): T = value
    }

    @Serializable(ModificationCoerceAtMostSerializer::class)
    data class CoerceAtMost<T: ComparableCodableAndHashable<T>>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = on.coerceAtMost(value)
        override fun invokeDefault(): T = value
    }

    @Serializable(ModificationCoerceAtLeastSerializer::class)
    data class CoerceAtLeast<T: ComparableCodableAndHashable<T>>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = on.coerceAtLeast(value)
        override fun invokeDefault(): T = value
    }

    @Serializable(ModificationIncrementSerializer::class)
    data class Increment<T: Number>(val by: T): Modification<T>() {
        override fun invoke(on: T): T = on + by
        override fun invokeDefault(): T = by
    }

    @Serializable(ModificationMultiplySerializer::class)
    data class Multiply<T: Number>(val by: T): Modification<T>() {
        override fun invoke(on: T): T = on * by
        override fun invokeDefault(): T = when(by) {
            is Byte -> 0.toByte() as T
            is Short -> 0.toShort() as T
            is Int -> 0 as T
            is Long -> 0L as T
            is Float -> 0f as T
            is Double -> 0.0 as T
            else -> fatalError()
        }
    }

    @Serializable(ModificationAppendStringSerializer::class)
    data class AppendString(val value: String): Modification<String>() {
        override fun invoke(on: String): String = on + value
        override fun invokeDefault(): String = value
    }

    @Serializable(ModificationAppendListSerializer::class)
    data class AppendList<T: IsCodableAndHashable>(val items: List<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on + items
        override fun invokeDefault(): List<T> = items
    }

    @Serializable(ModificationAppendSetSerializer::class)
    data class AppendSet<T: IsCodableAndHashable>(val items: List<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = (on + items).toSet().toList()
        override fun invokeDefault(): List<T> = items
    }

    @Serializable(ModificationRemoveSerializer::class)
    data class Remove<T: IsCodableAndHashable>(val condition: Condition<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.filter { !condition(it) }
        override fun invokeDefault(): List<T> = listOf()
    }

    @Serializable(ModificationRemoveInstancesSerializer::class)
    data class RemoveInstances<T: IsCodableAndHashable>(val items: List<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on - items
        override fun invokeDefault(): List<T> = listOf()
    }

    @Serializable(ModificationDropFirstSerializer::class)
    class DropFirst<T: IsCodableAndHashable>: Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.drop(1)
        override fun invokeDefault(): List<T> = listOf()
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.DropFirst<T>) != null
    }

    @Serializable(ModificationDropLastSerializer::class)
    class DropLast<T: IsCodableAndHashable>: Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.dropLast(1)
        override fun invokeDefault(): List<T> = listOf()
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.DropLast<T>) != null
    }

    @Serializable()
    @SerialName("PerElement")
    data class PerElement<T: IsCodableAndHashable>(val condition: Condition<T>, val modification: Modification<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.map { if(condition(it)) modification(it) else it }
        override fun invokeDefault(): List<T> = listOf()
    }

    @Serializable(ModificationCombineSerializer::class)
    data class Combine<T: IsCodableAndHashable>(val map: Map<String, T>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on + map
        override fun invokeDefault(): Map<String, T> = map
    }

    @Serializable(ModificationModifyByKeySerializer::class)
    data class ModifyByKey<T: IsCodableAndHashable>(val map: Map<String, Modification<T>>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on + map.mapValues { (on[it.key]?.let { e -> it.value(e) } ?: it.value.invokeDefault()) }
        override fun invokeDefault(): Map<String, T> = map.mapValues { it.value.invokeDefault() }
    }

    @Serializable(ModificationRemoveKeysSerializer::class)
    data class RemoveKeys<T: IsCodableAndHashable>(val fields: Set<String>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on.filterKeys { it !in fields }
        override fun invokeDefault(): Map<String, T> = mapOf()
    }

    data class OnField<K: IsCodableAndHashable, V: IsCodableAndHashable>(val key: DataClassProperty<K, V>, val modification: Modification<V>): Modification<K>() {
        override fun invoke(on: K): K = key.set(on, modification(key.get(on)))
        override fun invokeDefault(): K {
            fatalError("Cannot mutate a field that doesn't exist")
        }
    }
}
