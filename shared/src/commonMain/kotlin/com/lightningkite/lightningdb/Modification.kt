

package com.lightningkite.lightningdb

import com.lightningkite.serialization.SerializableProperty
import kotlinx.serialization.*

@Serializable(ModificationSerializer::class)
sealed class Modification<T>  {
    open override fun hashCode(): Int { throw NotImplementedError() }
    open override fun equals(other: Any?): Boolean { throw NotImplementedError() }
    open operator fun invoke(on: T): T { throw NotImplementedError() }
    open fun invokeDefault(): T { throw NotImplementedError() }
    open val isNothing: Boolean get() = false

    @Deprecated("Use the modification {} builder instead")
    infix fun then(other: Modification<T>): Modification.Chain<T> = Modification.Chain(listOf(this, other))

    @Serializable
    data object Nothing: Modification<Any?>() {
        override val isNothing: Boolean get() = true
        @Suppress("UNCHECKED_CAST")
        inline operator fun <T> invoke(): Modification<T> = this as Modification<T>
        override fun invoke(on: Any?): Any? = on
        override fun invokeDefault(): Any? = throw IllegalStateException()
        override fun toString(): String = ""
    }

    @Serializable(ModificationChainSerializer::class)
    data class Chain<T>(val modifications: List<Modification<T>>): Modification<T>() {
        override val isNothing: Boolean
            get() = modifications.all { it.isNothing }
        override fun invoke(on: T): T = modifications.fold(on) { item, mod -> mod(item) }
        override fun invokeDefault(): T {
            val on = modifications[0].invokeDefault()
            return modifications.drop(1).fold(on) { item, mod -> mod(item) }
        }
        override fun toString(): String = modifications.joinToString("; ")
    }

    @Serializable(ModificationIfNotNullSerializer::class)
    data class IfNotNull<T>(val modification: Modification<T>): Modification<T?>() {
        override fun invoke(on: T?): T? = on?.let { modification(it) }
        override fun invokeDefault(): T? = null
        override fun toString(): String = "?$modification"
    }

    @Serializable(ModificationAssignSerializer::class)
    data class Assign<T>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = value
        override fun invokeDefault(): T = value
        override fun toString(): String = " = $value"
    }

    @Serializable(ModificationCoerceAtMostSerializer::class)
    data class CoerceAtMost<T: Comparable<T>>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = on.coerceAtMost(value)
        override fun invokeDefault(): T = value
        override fun toString(): String = " = .coerceAtMost($value)"
    }

    @Serializable(ModificationCoerceAtLeastSerializer::class)
    data class CoerceAtLeast<T: Comparable<T>>(val value: T): Modification<T>() {
        override fun invoke(on: T): T = on.coerceAtLeast(value)
        override fun invokeDefault(): T = value
        override fun toString(): String = " = .coerceAtLeast($value)"
    }

    @Serializable(ModificationIncrementSerializer::class)
    data class Increment<T: Number>(val by: T): Modification<T>() {
        override fun invoke(on: T): T = on + by
        override fun invokeDefault(): T = by
        override fun toString(): String = " += $by"
    }

    @Serializable(ModificationMultiplySerializer::class)
    data class Multiply<T: Number>(val by: T): Modification<T>() {
        override fun invoke(on: T): T = on * by
        @Suppress("UNCHECKED_CAST")
        override fun invokeDefault(): T = when(by) {
            is Byte -> 0.toByte() as T
            is Short -> 0.toShort() as T
            is Int -> 0 as T
            is Long -> 0L as T
            is Float -> 0f as T
            is Double -> 0.0 as T
            else -> throw NotImplementedError()
        }
        override fun toString(): String = " *= $by"
    }

    @Serializable(ModificationAppendStringSerializer::class)
    data class AppendString(val value: String): Modification<String>() {
        override fun invoke(on: String): String = on + value
        override fun invokeDefault(): String = value
        override fun toString(): String = " += $value"
    }

    @Serializable(ModificationListAppendSerializer::class)
    data class ListAppend<T>(val items: List<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on + items
        override fun invokeDefault(): List<T> = items
        override fun toString(): String = " += $items"
    }

    @Serializable(ModificationListRemoveSerializer::class)
    data class ListRemove<T>(val condition: Condition<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.filter { !condition(it) }
        override fun invokeDefault(): List<T> = listOf()
        override fun toString(): String = ".removeAll { it.$condition }"
    }

    @Serializable(ModificationListRemoveInstancesSerializer::class)
    data class ListRemoveInstances<T>(val items: List<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on - items
        override fun invokeDefault(): List<T> = listOf()
        override fun toString(): String = " -= $items"
    }

    @Serializable(ModificationListDropFirstSerializer::class)
    class ListDropFirst<T>: Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.drop(1)
        override fun invokeDefault(): List<T> = listOf()
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.ListDropFirst<T>) != null
        override fun toString(): String = ".removeFirst()"
    }

    @Serializable(ModificationListDropLastSerializer::class)
    class ListDropLast<T>: Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.dropLast(1)
        override fun invokeDefault(): List<T> = listOf()
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.ListDropLast<T>) != null
        override fun toString(): String = ".removeLast()"
    }

    @Serializable()
    @SerialName("ListPerElement")
    data class ListPerElement<T>(val condition: Condition<T>, val modification: Modification<T>): Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.map { if(condition(it)) modification(it) else it }
        override fun invokeDefault(): List<T> = listOf()
        override fun toString(): String = ".onEach { if (it.$condition) it.$modification }"
    }

    @Serializable(ModificationSetAppendSerializer::class)
    data class SetAppend<T>(val items: Set<T>): Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = (on + items)
        override fun invokeDefault(): Set<T> = items
        override fun toString(): String = " += $items"
    }

    @Serializable(ModificationSetRemoveSerializer::class)
    data class SetRemove<T>(val condition: Condition<T>): Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.filter { !condition(it) }.toSet()
        override fun invokeDefault(): Set<T> = setOf()
        override fun toString(): String = ".removeAll { it.$condition }"
    }

    @Serializable(ModificationSetRemoveInstancesSerializer::class)
    data class SetRemoveInstances<T>(val items: Set<T>): Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on - items
        override fun invokeDefault(): Set<T> = setOf()
        override fun toString(): String = " -= $items"
    }

    @Serializable(ModificationSetDropFirstSerializer::class)
    class SetDropFirst<T>: Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.drop(1).toSet()
        override fun invokeDefault(): Set<T> = setOf()
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.SetDropFirst<T>) != null
        override fun toString(): String = ".removeFirst()"
    }

    @Serializable(ModificationSetDropLastSerializer::class)
    class SetDropLast<T>: Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.toList().dropLast(1).toSet()
        override fun invokeDefault(): Set<T> = setOf()
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Modification.SetDropLast<T>) != null
        override fun toString(): String = ".removeLast()"
    }

    @Serializable()
    @SerialName("SetPerElement")
    data class SetPerElement<T>(val condition: Condition<T>, val modification: Modification<T>): Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.map { if(condition(it)) modification(it) else it }.toSet()
        override fun invokeDefault(): Set<T> = setOf()
        override fun toString(): String = ".onEach { if ($condition) $modification }"
    }

    @Serializable(ModificationCombineSerializer::class)
    data class Combine<T>(val map: Map<String, T>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on + map
        override fun invokeDefault(): Map<String, T> = map
        override fun toString(): String = " += $map"
    }

    @Serializable(ModificationModifyByKeySerializer::class)
    data class ModifyByKey<T>(val map: Map<String, Modification<T>>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on + map.mapValues { (on[it.key]?.let { e -> it.value(e) } ?: it.value.invokeDefault()) }
        override fun invokeDefault(): Map<String, T> = map.mapValues { it.value.invokeDefault() }
    }

    @Serializable(ModificationRemoveKeysSerializer::class)
    data class RemoveKeys<T>(val fields: Set<String>): Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on.filterKeys { it !in fields }
        override fun invokeDefault(): Map<String, T> = mapOf()
        override fun toString(): String = " -= $fields"
    }

    data class OnField<K, V>(val key: SerializableProperty<K, V>, val modification: Modification<V>): Modification<K>() {
        override fun invoke(on: K): K = key.setCopy(on, modification(key.get(on)))
        override fun invokeDefault(): K {
            throw IllegalStateException("Cannot mutate a field that doesn't exist")
        }
        override fun toString(): String {
            return if(modification is Modification.OnField<*, *>)
                "${key.name}.$modification"
            else if(modification is Modification.Chain<*>)
                "${key.name}.let { $modification }"
            else
                "${key.name}$modification"
        }
    }
}
